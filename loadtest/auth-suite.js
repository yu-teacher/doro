// auth 인증/인가 엔드포인트 부하 테스트. 시나리오는 SCENARIO 로 고른다.
//   k6 run -e SCENARIO=lookup -e VUS=50 -e DURATION=15s auth-suite.js
// SCENARIO: admin-list-paged | lookup | public | login-fail | sessions | session-churn | revoke-others | 2fa-login | 2fa-enroll
//           admin-read | admin-write | oauth-flow | oauth-refresh | userinfo
// 로컬 auth 는 compose.loadtest.yml 로 띄운다(rate limit 해제, 부트스트랩 토큰).
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { AUTH, JSONH, PASSWORD, bearer, makeUsers, login, signup, totp, totpStep, pkce } from './lib.js';

const SC = __ENV.SCENARIO || 'lookup';
const VUS = parseInt(__ENV.VUS || '20');
const USERS = parseInt(__ENV.USERS || '200');
const BOOTSTRAP_TOKEN = __ENV.BOOTSTRAP_TOKEN || 'loadtest-bootstrap-token-local-only';
const REDIRECT = 'http://localhost:9999/cb';
const ADMIN_EMAIL = 'lt-admin@loadtest.local';

const bad5xx = new Counter('server_5xx');
const wrong = new Counter('wrong_answer');
export const options = {
  scenarios: { s: { executor: 'constant-vus', vus: VUS, duration: __ENV.DURATION || '15s' } },
  setupTimeout: '600s',
  thresholds: { server_5xx: ['count==0'], wrong_answer: ['count==0'] },
};

function adminLogin() {
  try { const d = login(ADMIN_EMAIL); if (d.tokens) return d.tokens.accessToken; } catch (e) { /* 아직 없음 */ }
  signup(ADMIN_EMAIL, 'ltadmin');
  const t = login(ADMIN_EMAIL).tokens.accessToken;
  const b = http.post(`${AUTH}/api/v1/auth/bootstrap`, JSON.stringify({ token: BOOTSTRAP_TOKEN }), bearer(t));
  if (b.status >= 300) throw new Error(`bootstrap ${b.status} ${b.body}`);
  return login(ADMIN_EMAIL).tokens.accessToken;   // 역할이 바뀌어 세션이 끝나므로 다시 로그인
}

export function setup() {
  const d = { users: [], run: Date.now() };
  switch (SC) {
    case 'lookup': case 'login-fail': case 'userinfo': d.users = makeUsers(USERS, 'u'); break;
    case 'sessions': d.users = makeUsers(USERS, 'ss', 3); break;
    case 'revoke-others': d.users = makeUsers(Math.max(VUS, USERS), 'ro'); break;
    case 'session-churn': d.users = makeUsers(Math.max(VUS, 20), 'ch'); break;
    case 'oauth-flow': case 'oauth-refresh': {
      d.users = makeUsers(Math.max(VUS, 20), 'oa');
      d.admin = adminLogin();
      const c = http.post(`${AUTH}/api/v1/admin/oauth/clients`, JSON.stringify({ name: `lt-${d.run}`, redirectUris: [REDIRECT], firstParty: true, confidential: false }), bearer(d.admin));
      if (c.status >= 300) throw new Error(`client ${c.status} ${c.body}`);
      d.clientId = c.json('data.clientId');
      break;
    }
    case 'admin-read': case 'admin-list-paged': case 'admin-write': d.admin = adminLogin(); d.users = makeUsers(USERS, 'ad'); break;
    case '2fa-login': case '2fa-enroll': {
      d.users = makeUsers(USERS, 'tf');
      if (SC === '2fa-login') {
        for (const u of d.users) {
          const s = http.post(`${AUTH}/api/v1/auth/2fa/setup`, JSON.stringify({ currentPassword: PASSWORD }), bearer(u.access));
          if (s.status !== 200) throw new Error(`2fa setup ${s.status} ${s.body}`);
          u.secret = s.json('data.secret');
          const v = http.post(`${AUTH}/api/v1/auth/2fa/verify`, JSON.stringify({ code: totp(u.secret) }), bearer(u.access));
          if (v.status !== 200) throw new Error(`2fa verify ${v.status} ${v.body}`);
          u.lastStep = totpStep();
        }
      }
      break;
    }
  }
  return d;
}

const state = {};   // VU 로컬
const T = (name) => ({ tags: { name } });
const ok = (res, name, pred) => {
  if (res.status >= 500 || res.status === 0) bad5xx.add(1);
  const pass = check(res, { [name]: pred });
  if (!pass && __ENV.DEBUG) console.log(`[${name}] ${res.status} ${String(res.body).slice(0, 160)}`);
  return pass;
};

export default function (d) {
  const vu = exec.vu.idInTest;
  const rndUser = () => d.users[Math.floor(Math.random() * d.users.length)];
  const myUser = () => d.users[(vu - 1) % d.users.length];
  let r;
  switch (SC) {
    case 'lookup': {
      const exists = Math.random() < 0.5;
      const email = exists ? rndUser().email : `nobody-${Math.random()}@loadtest.local`;
      r = http.post(`${AUTH}/api/v1/auth/lookup`, JSON.stringify({ email }), { headers: JSONH, ...T('lookup'), responseCallback: http.expectedStatuses(200, 404) });
      ok(r, 'lookup 200/404', (x) => x.status === 200 || x.status === 404);
      break;
    }
    case 'public': {
      const rs = http.batch([
        ['GET', `${AUTH}/.well-known/jwks.json`, null, T('jwks')],
        ['GET', `${AUTH}/.well-known/openid-configuration`, null, T('discovery')],
        ['GET', `${AUTH}/health`, null, T('health')],
      ]);
      rs.forEach((x) => ok(x, 'public 200', (y) => y.status === 200));
      break;
    }
    case 'login-fail': {
      // 비밀번호 틀림 + 존재하지 않는 계정. 잠금(423 등)과 4xx 만 허용, 5xx 는 실패.
      const u = rndUser();
      const email = Math.random() < 0.8 ? u.email : `nobody-${Math.random()}@loadtest.local`;
      r = http.post(`${AUTH}/api/v1/auth/login`, JSON.stringify({ email, password: 'Wrong!Pass123' }),
        { headers: JSONH, ...T('login-fail'), responseCallback: http.expectedStatuses({ min: 200, max: 499 }) });
      ok(r, 'login-fail 4xx', (x) => x.status >= 400 && x.status < 500);
      break;
    }
    case 'sessions': {
      const u = rndUser();
      const rs = http.batch([
        ['GET', `${AUTH}/api/v1/sessions`, null, { ...bearer(u.access), ...T('sessions-list') }],
        ['GET', `${AUTH}/api/v1/sessions/current`, null, { ...bearer(u.access), ...T('sessions-current') }],
      ]);
      rs.forEach((x) => ok(x, 'sessions 200/204', (y) => y.status === 200 || y.status === 204));
      break;
    }
    case 'revoke-others': {
      // 세션 하나를 더 만든 뒤 다른 세션 전부 종료
      const u = myUser();
      const l = http.post(`${AUTH}/api/v1/auth/login`, JSON.stringify({ email: u.email, password: PASSWORD, deviceInfo: 'k6x' }), { headers: JSONH, ...T('login') });
      if (l.status !== 200) { ok(l, 'login 200', (x) => x.status === 200); break; }
      const tok = l.json('data.tokens.accessToken');
      r = http.post(`${AUTH}/api/v1/sessions/revoke-others?currentSessionId=${l.json('data.tokens.sessionId')}`, null, { ...bearer(tok), ...T('revoke-others') });
      ok(r, 'revoke-others 2xx', (x) => x.status >= 200 && x.status < 300);
      break;
    }
    case 'session-churn': {
      const u = myUser();
      const l = http.post(`${AUTH}/api/v1/auth/login`, JSON.stringify({ email: u.email, password: PASSWORD, deviceInfo: 'k6c' }), { headers: JSONH, ...T('login') });
      if (!ok(l, 'login 200', (x) => x.status === 200)) break;
      const t = l.json('data.tokens.accessToken');
      const me = http.get(`${AUTH}/api/v1/users/me`, { ...bearer(t), ...T('me') });
      ok(me, 'me 200', (x) => x.status === 200);
      const lo = http.post(`${AUTH}/api/v1/auth/logout`, null, { ...bearer(t), ...T('logout') });
      ok(lo, 'logout 2xx', (x) => x.status >= 200 && x.status < 300);
      const after = http.get(`${AUTH}/api/v1/users/me`, { ...bearer(t), ...T('me-after-logout'), responseCallback: http.expectedStatuses({ min: 200, max: 499 }) });
      // 로그아웃한 토큰은 거부돼야 한다(킬스위치). 200 이면 인가 결함.
      if (after.status === 200) wrong.add(1);
      break;
    }
    case '2fa-enroll': {
      // 사용자당 한 번만 등록한다(같은 사용자·같은 30초 구간의 코드는 서버가 재사용을 막으므로 해제 후 재등록은 하지 않는다).
      const idx = exec.scenario.iterationInTest;
      if (idx >= d.users.length) { sleep(1); break; }
      const u = d.users[idx];
      const l = http.post(`${AUTH}/api/v1/auth/login`, JSON.stringify({ email: u.email, password: PASSWORD, deviceInfo: 'k6e' }), { headers: JSONH, ...T('login') });
      if (!ok(l, 'login 200', (x) => x.status === 200)) break;
      const t = l.json('data.tokens.accessToken');
      const s = http.post(`${AUTH}/api/v1/auth/2fa/setup`, JSON.stringify({ currentPassword: PASSWORD }), { ...bearer(t), ...T('2fa-setup') });
      if (!ok(s, '2fa setup 200', (x) => x.status === 200)) break;
      const v = http.post(`${AUTH}/api/v1/auth/2fa/verify`, JSON.stringify({ code: totp(s.json('data.secret')) }), { ...bearer(t), ...T('2fa-verify') });
      ok(v, '2fa verify 200', (x) => x.status === 200);
      break;
    }
    case '2fa-login': {
      // 같은 사용자·같은 30초 구간의 코드는 재사용되지 않으므로, 이번 구간에 아직 안 쓴 사용자만 고른다.
      const step = totpStep();
      let u = null;
      // VU 마다 상태 복사본이 따로라서, 사용자를 VU 별로 나눠 같은 사용자를 두 VU 가 쓰지 않게 한다.
      const mine = d.users.filter((_, i) => i % VUS === vu - 1);
      for (let k = 0; k < mine.length; k++) { const c = mine[Math.floor(Math.random() * mine.length)]; if (c.lastStep !== step) { u = c; break; } }
      if (!u) { sleep(1); break; }
      u.lastStep = step;
      const l = http.post(`${AUTH}/api/v1/auth/login`, JSON.stringify({ email: u.email, password: PASSWORD, deviceInfo: 'k6t' }), { headers: JSONH, ...T('login-2fa-step1') });
      if (!ok(l, 'login step1 200', (x) => x.status === 200)) break;
      if (!l.json('data.requires2fa')) { wrong.add(1); break; }
      r = http.post(`${AUTH}/api/v1/auth/2fa/login`, JSON.stringify({ tempTicket: l.json('data.tempTicket'), code: totp(u.secret, step), deviceInfo: 'k6t' }), { headers: JSONH, ...T('2fa-login') });
      ok(r, '2fa login 200', (x) => x.status === 200);
      break;
    }
    case 'admin-read': {
      const rs = http.batch([
        ['GET', `${AUTH}/api/v1/admin/users`, null, { ...bearer(d.admin), ...T('admin-users') }],
        ['GET', `${AUTH}/api/v1/admin/authz`, null, { ...bearer(d.admin), ...T('admin-authz') }],
        ['GET', `${AUTH}/api/v1/admin/oauth/clients`, null, { ...bearer(d.admin), ...T('admin-clients') }],
      ]);
      ok(rs[0], 'admin users 200', (x) => x.status === 200);
      ok(rs[1], 'admin authz 204', (x) => x.status === 204);
      ok(rs[2], 'admin clients 200', (x) => x.status === 200);
      // 일반 사용자는 거부돼야 한다
      const n = http.get(`${AUTH}/api/v1/admin/users`, { ...bearer(rndUser().access), ...T('admin-users-as-user'), responseCallback: http.expectedStatuses({ min: 200, max: 499 }) });
      if (n.status === 200) wrong.add(1);
      break;
    }
    case 'admin-list-paged': {
      r = http.get(`${AUTH}/api/v1/admin/users?page=${Math.floor(Math.random() * 20)}&size=50`, { ...bearer(d.admin), ...T('admin-users-paged') });
      ok(r, 'admin users paged 200', (x) => x.status === 200 && x.headers['X-Total-Count'] !== undefined);
      break;
    }
    case 'admin-write': {
      const t = rndUser();
      const op = Math.floor(Math.random() * 4);
      if (op === 0) {
        r = http.put(`${AUTH}/api/v1/admin/users/${t.userId}/suspension`, JSON.stringify({ reason: 'lt' }), { ...bearer(d.admin), ...T('suspend') });
        ok(r, 'suspend 2xx', (x) => x.status >= 200 && x.status < 300);
        r = http.del(`${AUTH}/api/v1/admin/users/${t.userId}/suspension`, null, { ...bearer(d.admin), ...T('unsuspend') });
        ok(r, 'unsuspend 2xx', (x) => x.status >= 200 && x.status < 300);
      } else if (op === 1) {
        r = http.del(`${AUTH}/api/v1/admin/users/${t.userId}/lock`, null, { ...bearer(d.admin), ...T('unlock') });
        ok(r, 'unlock 2xx', (x) => x.status >= 200 && x.status < 300);
      } else if (op === 2) {
        r = http.patch(`${AUTH}/api/v1/admin/users/${t.userId}/role`, JSON.stringify({ role: 'ADMIN' }), { ...bearer(d.admin), ...T('role-up') });
        ok(r, 'role up 2xx', (x) => x.status >= 200 && x.status < 300);
        r = http.patch(`${AUTH}/api/v1/admin/users/${t.userId}/role`, JSON.stringify({ role: 'USER' }), { ...bearer(d.admin), ...T('role-down') });
        ok(r, 'role down 2xx', (x) => x.status >= 200 && x.status < 300);
      } else {
        r = http.del(`${AUTH}/api/v1/admin/users/${t.userId}/2fa`, null, { ...bearer(d.admin), ...T('reset-2fa'), responseCallback: http.expectedStatuses({ min: 200, max: 499 }) });
        ok(r, 'reset 2fa no 5xx', (x) => x.status < 500);
      }
      break;
    }
    case 'oauth-flow': case 'oauth-refresh': {
      const u = myUser();
      if (!state.access || SC === 'oauth-flow') {
        const l = http.post(`${AUTH}/api/v1/auth/login`, JSON.stringify({ email: u.email, password: PASSWORD, deviceInfo: 'k6o' }), { headers: JSONH, ...T('login') });
        if (!ok(l, 'login 200', (x) => x.status === 200)) break;
        state.access = l.json('data.tokens.accessToken');
      }
      if (SC === 'oauth-flow' || !state.refresh) {
        const p = pkce();
        const a = http.get(`${AUTH}/oauth2/authorize?client_id=${d.clientId}&redirect_uri=${encodeURIComponent(REDIRECT)}&response_type=code&scope=openid&state=s1&code_challenge=${p.challenge}&code_challenge_method=S256`,
          { ...bearer(state.access), ...T('authorize') });
        if (!ok(a, 'authorize 200', (x) => x.status === 200)) break;
        const t = http.post(`${AUTH}/oauth2/token`, JSON.stringify({ grantType: 'authorization_code', code: a.json('data.code'), redirectUri: REDIRECT, clientId: d.clientId, codeVerifier: p.verifier }),
          { headers: JSONH, ...T('token-code') });
        if (!ok(t, 'token 200', (x) => x.status === 200)) break;
        state.refresh = t.json('data.refreshToken');
        const at = t.json('data.accessToken');
        const ui = http.get(`${AUTH}/oauth2/userinfo`, { ...bearer(at), ...T('userinfo') });
        ok(ui, 'userinfo 200', (x) => x.status === 200);
        if (SC === 'oauth-flow') {
          const rv = http.post(`${AUTH}/oauth2/revoke`, { token: state.refresh, client_id: d.clientId }, T('revoke'));
          ok(rv, 'revoke 200', (x) => x.status === 200);
          state.refresh = null;
          break;
        }
      }
      r = http.post(`${AUTH}/oauth2/token`, JSON.stringify({ grantType: 'refresh_token', refreshToken: state.refresh, clientId: d.clientId }), { headers: JSONH, ...T('token-refresh') });
      if (ok(r, 'refresh 200', (x) => x.status === 200)) state.refresh = r.json('data.refreshToken');
      break;
    }
    case 'userinfo': {
      r = http.get(`${AUTH}/api/v1/users/me`, { ...bearer(rndUser().access), ...T('me') });
      ok(r, 'me 200', (x) => x.status === 200);
      break;
    }
  }
}

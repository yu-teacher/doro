// 운영 서버용 저부하 스모크 테스트. 인증 경로(토큰 갱신, 내 정보)와 공개 경로를 낮은 속도로 확인한다.
//
//   CONFIRM_PROD=yes BASE=https://<운영 주소> TEST_EMAIL=... TEST_PASSWORD=... \
//     k6 run -e VUS=8 -e DURATION=30s loadtest/prod-smoke.js
//
// 안전장치
// - CONFIRM_PROD=yes 가 없으면 시작하지 않는다.
// - 계정을 만들지 않는다. 미리 만들어 둔 **전용 테스트 계정** 하나만 쓴다(개인 계정 금지: 사용자당 활성 세션 상한이
//   10개라서 로그인하면 그 계정의 다른 기기 세션이 밀려 로그아웃된다).
// - 로그인은 VUS 번만 한다(운영 rate limit: IP당 10분에 20회). 이후는 토큰 갱신과 조회만 한다.
// - 비밀번호는 환경변수로만 받는다. 파일에 적거나 로그로 남기지 않는다.
// - 오류율 2% 초과 또는 p95 1.5초 초과면 스스로 중단한다.
// - 끝나면 만든 세션을 모두 로그아웃한다.
import http from 'k6/http';
import { check, sleep, fail } from 'k6';
import exec from 'k6/execution';

const BASE = (__ENV.BASE || '').replace(/\/$/, '');
const EMAIL = __ENV.TEST_EMAIL || '';
const PASSWORD = __ENV.TEST_PASSWORD || '';
const VUS = Math.min(parseInt(__ENV.VUS || '8'), 10);   // 세션 상한(10)을 넘기지 않는다
const PAUSE = parseFloat(__ENV.PAUSE || '0.5');          // 반복 사이 쉬는 시간(초). VU 당 약 2회/초
const H = { 'Content-Type': 'application/json' };
const auth = (t) => ({ headers: { ...H, Authorization: `Bearer ${t}` } });

export const options = {
  scenarios: { smoke: { executor: 'constant-vus', vus: VUS, duration: __ENV.DURATION || '30s', gracefulStop: '10s' } },
  setupTimeout: '120s',
  thresholds: {
    http_req_failed: [{ threshold: 'rate<0.02', abortOnFail: true, delayAbortEval: '5s' }],
    http_req_duration: [{ threshold: 'p(95)<1500', abortOnFail: true, delayAbortEval: '5s' }],
  },
};

// 만든 세션을 로그아웃한다(실패해도 계속). setup 이 중간에 중단돼도 세션이 남지 않게 한다.
function logoutAll(sessions) {
  for (const s of sessions) {
    http.post(`${BASE}/api/v1/auth/logout?sessionId=${s.sessionId}`, null, { ...auth(s.access), responseCallback: http.expectedStatuses({ min: 200, max: 499 }) });
  }
}

export function setup() {
  if (__ENV.CONFIRM_PROD !== 'yes') fail('운영 대상 테스트입니다. 확인했다면 CONFIRM_PROD=yes 를 주세요.');
  if (!/^https?:\/\//.test(BASE)) fail('BASE 가 필요합니다(예: https://example.com).');
  if (!EMAIL || !PASSWORD) fail('TEST_EMAIL, TEST_PASSWORD 환경변수가 필요합니다(전용 테스트 계정).');

  const sessions = [];
  for (let i = 0; i < VUS; i++) {
    // 서버는 같은 기기(IP + User-Agent)의 이전 세션을 자동으로 끝낸다. 세션을 VUS 개 만들려면 User-Agent 를 달리해야 한다.
    const r = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({ email: EMAIL, password: PASSWORD, deviceInfo: `k6-smoke-${i}` }),
      { headers: { ...H, 'User-Agent': `k6-smoke/${i}` } });
    if (r.status === 429) { logoutAll(sessions); fail('로그인 rate limit(429)에 걸렸습니다. 10분 뒤에 다시 하거나 VUS 를 줄이세요.'); }
    if (r.status !== 200) { logoutAll(sessions); fail(`로그인 실패 status=${r.status} (계정/비밀번호/주소를 확인하세요)`); }
    const d = r.json('data');
    if (d.requires2fa) { logoutAll(sessions); fail('테스트 계정에 2FA 가 켜져 있습니다. 2FA 없는 전용 계정을 쓰세요.'); }
    sessions.push({ access: d.tokens.accessToken, refresh: d.tokens.refreshToken, sessionId: d.tokens.sessionId });
    sleep(1);   // 해싱 비용이 큰 로그인은 천천히
  }
  return { sessions };
}

let mine = null;   // VU 로컬: 이 VU 의 세션(토큰은 갱신마다 바뀐다)

export default function (data) {
  if (!mine) mine = { ...data.sessions[(exec.vu.idInTest - 1) % data.sessions.length] };

  const rf = http.post(`${BASE}/api/v1/auth/token/refresh`, JSON.stringify({ refreshToken: mine.refresh }), { headers: H, tags: { name: 'refresh' } });
  if (check(rf, { 'refresh 200': (r) => r.status === 200 })) {
    mine.access = rf.json('data.accessToken');
    mine.refresh = rf.json('data.refreshToken');
  }
  const me = http.get(`${BASE}/api/v1/users/me`, { ...auth(mine.access), tags: { name: 'me' } });
  check(me, { 'me 200': (r) => r.status === 200 });
  if (Math.random() < 0.1) {
    const j = http.get(`${BASE}/.well-known/jwks.json`, { tags: { name: 'jwks' } });
    check(j, { 'jwks 200': (r) => r.status === 200 });
  }
  sleep(PAUSE);
}

export function teardown(data) {
  // 갱신으로 토큰이 바뀌었을 수 있어 로그아웃이 실패해도 괜찮다(세션은 서버에서 만료된다).
  logoutAll(data.sessions);
}

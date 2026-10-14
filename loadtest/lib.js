// 부하 테스트 공용 도우미: 사용자 준비, TOTP, PKCE.
import http from 'k6/http';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

export const AUTH = __ENV.AUTH || 'http://localhost:28080';
export const GUARD = __ENV.GUARD || 'http://localhost:28081';
export const PASSWORD = 'LoadTest!2345';
export const JSONH = { 'Content-Type': 'application/json' };
export const bearer = (t) => ({ headers: { ...JSONH, Authorization: `Bearer ${t}` } });

export function signup(email, name = 'lt') {
  const r = http.post(`${AUTH}/api/v1/auth/signup`, JSON.stringify({ email, password: PASSWORD, name }), { headers: JSONH });
  if (r.status >= 300) throw new Error(`signup ${r.status} ${r.body}`);
  return r.json('data.userId');
}

export function login(email, password = PASSWORD) {
  const r = http.post(`${AUTH}/api/v1/auth/login`, JSON.stringify({ email, password, deviceInfo: 'k6' }), { headers: JSONH });
  if (r.status !== 200) throw new Error(`login ${r.status} ${r.body}`);
  return r.json('data');   // { requires2fa, tokens:{accessToken,refreshToken,sessionId}, tempTicket? }
}

// n 명을 가입시키고 로그인한다. 해싱 동시성 제한(서버 8)을 넘지 않게 8개씩 묶어 병렬 호출한다.
export function makeUsers(n, tag, sessionsEach = 1) {
  const run = Date.now();
  const users = [];
  for (let i = 0; i < n; i += 8) {
    const batch = [];
    for (let j = i; j < Math.min(n, i + 8); j++) {
      const email = `${tag}-${run}-${j}@loadtest.local`;
      batch.push({ email, req: ['POST', `${AUTH}/api/v1/auth/signup`, JSON.stringify({ email, password: PASSWORD, name: `${tag}${j}` }), { headers: JSONH }] });
    }
    const rs = http.batch(batch.map((b) => b.req));
    rs.forEach((r, k) => { if (r.status >= 300) throw new Error(`signup ${r.status} ${r.body}`); users.push({ email: batch[k].email, userId: r.json('data.userId') }); });
  }
  for (let i = 0; i < users.length; i += 8) {
    const slice = users.slice(i, i + 8);
    for (let s = 0; s < sessionsEach; s++) {
      const rs = http.batch(slice.map((u) => ['POST', `${AUTH}/api/v1/auth/login`, JSON.stringify({ email: u.email, password: PASSWORD, deviceInfo: `k6-${s}` }), { headers: JSONH }]));
      rs.forEach((r, k) => {
        if (r.status !== 200) throw new Error(`login ${r.status} ${r.body}`);
        const t = r.json('data.tokens');
        slice[k].access = t.accessToken; slice[k].refresh = t.refreshToken; slice[k].sessionId = t.sessionId;
      });
    }
  }
  return users;
}

// ---- TOTP (RFC 6238, SHA1, 6자리, 30초)
const B32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
function b32decode(s) {
  let bits = '';
  for (const c of s.replace(/=+$/, '').toUpperCase()) bits += B32.indexOf(c).toString(2).padStart(5, '0');
  const bytes = new Uint8Array(Math.floor(bits.length / 8));
  for (let i = 0; i < bytes.length; i++) bytes[i] = parseInt(bits.substr(i * 8, 8), 2);
  return bytes.buffer;
}
export const totpStep = (t = Date.now()) => Math.floor(t / 30000);
export function totp(secret, step = totpStep()) {
  const msg = new Uint8Array(8);
  let c = step;
  for (let i = 7; i >= 0; i--) { msg[i] = c & 0xff; c = Math.floor(c / 256); }
  const h = new Uint8Array(crypto.hmac('sha1', b32decode(secret), msg.buffer, 'binary'));
  const o = h[h.length - 1] & 0xf;
  const bin = ((h[o] & 0x7f) << 24) | (h[o + 1] << 16) | (h[o + 2] << 8) | h[o + 3];
  return String(bin % 1000000).padStart(6, '0');
}

// ---- PKCE
export function pkce() {
  const verifier = encoding.b64encode(crypto.randomBytes(32), 'rawurl');
  const challenge = encoding.b64encode(crypto.sha256(verifier, 'binary'), 'rawurl');
  return { verifier, challenge };
}

export const sleepJitter = (sleep, ms = 100) => sleep(Math.random() * ms / 1000);

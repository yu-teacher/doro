// 외부 앱 시험 준비: 첫 최고 관리자 → 외부 앱용 OAuth 클라이언트 등록(firstParty=false: 기밀 1, 공개 1) → 시험용 일반 계정.
// 비밀번호·client_secret 은 실행마다 무작위이며 .state/third-party.json(권한 600)에만 쓴다. 로그에는 값을 남기지 않는다.
import { randomBytes } from 'node:crypto';
import { mkdirSync, writeFileSync, chmodSync, readFileSync } from 'node:fs';

const fileEnv = Object.fromEntries(readFileSync(new URL('./e2e.env', import.meta.url), 'utf8')
  .split('\n').filter((l) => l && !l.startsWith('#')).map((l) => l.split('=')));
const env = { ...fileEnv, ...Object.fromEntries(Object.entries(process.env).filter(([k]) => k.startsWith('E2E_'))) };
const IAM = `http://localhost:${env.E2E_AUTH_PORT}`;
const PORTAL = `http://localhost:${env.E2E_PORTAL_PORT}`;
const APP_PORT = Number(env.E2E_EXT_APP_PORT ?? 19001);
const APP = `http://127.0.0.1:${APP_PORT}`;

const randomPassword = () => `${randomBytes(12).toString('base64url')}Aa1!`;
const tag = randomBytes(4).toString('hex');

async function api(method, path, { token, body, expect = [200, 201] } = {}) {
  const res = await fetch(IAM + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  if (!expect.includes(res.status)) throw new Error(`${method} ${path} -> HTTP ${res.status} ${text.slice(0, 200)}`);
  return text ? JSON.parse(text) : {};
}
async function signupAndLogin(email, password, name) {
  await api('POST', '/api/v1/auth/signup', { body: { email, password, name } });
  const login = await api('POST', '/api/v1/auth/login', { body: { email, password, deviceInfo: 'e2e-setup' } });
  return login.data.tokens.accessToken;
}

const state = { iam: IAM, portal: PORTAL, issuer: PORTAL, app: APP, appPort: APP_PORT };
state.adminEmail = `ext-admin-${tag}@doro.local`;
state.adminPassword = randomPassword();
const adminFirst = await signupAndLogin(state.adminEmail, state.adminPassword, 'Ext Admin');
await api('POST', '/api/v1/auth/bootstrap', { token: adminFirst, body: { token: env.E2E_BOOTSTRAP_TOKEN } });
const adminLogin = await api('POST', '/api/v1/auth/login', { body: { email: state.adminEmail, password: state.adminPassword, deviceInfo: 'e2e-setup' } });
const adminToken = adminLogin.data.tokens.accessToken;
state.adminToken = adminToken;

// 외부 앱(제3자): firstParty=false → 동의 화면을 거친다. 허용 scope 는 openid/profile/email 만.
const confidential = await api('POST', '/api/v1/admin/oauth/clients', {
  token: adminToken,
  body: { name: '시험 외부 앱', clientId: 'ext-confidential', firstParty: false, confidential: true, redirectUris: [`${APP}/callback`], scopes: ['openid', 'profile', 'email'] },
});
state.confidentialId = confidential.data.clientId;
state.confidentialSecret = confidential.data.clientSecret;
const pub = await api('POST', '/api/v1/admin/oauth/clients', {
  token: adminToken,
  body: { name: '시험 공개 앱', clientId: 'ext-public', firstParty: false, confidential: false, redirectUris: [`${APP}/callback`], scopes: ['openid'] },
});
state.publicId = pub.data.clientId;
console.log('[setup] 외부 앱 클라이언트 등록: ext-confidential(기밀), ext-public(공개)');

state.userEmail = `ext-user-${tag}@doro.local`;
state.userPassword = randomPassword();
state.userName = 'Ext User';
await signupAndLogin(state.userEmail, state.userPassword, state.userName);
console.log(`[setup] 시험 계정: ${state.userEmail}`);

mkdirSync(new URL('./.state/', import.meta.url), { recursive: true });
const file = new URL('./.state/third-party.json', import.meta.url);
writeFileSync(file, JSON.stringify(state, null, 2));
chmodSync(file, 0o600);

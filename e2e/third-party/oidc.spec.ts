import { test, expect, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { createHash, randomBytes } from 'node:crypto';
import { createServer, type Server } from 'node:http';
import * as oidc from 'openid-client';

/**
 * "Doro 로 로그인"을 붙이는 외부(제3자) 앱의 시점에서 본 시험.
 * 외부 앱은 표준 OIDC 클라이언트 라이브러리(openid-client)로 만든 가짜 서버이고, 서비스 코드(SDK·BFF)를 쓰지 않는다.
 * 그래서 "구글·카카오 로그인처럼 표준만 알면 붙는가"를 본다. 발급자(issuer)는 운영처럼 한 주소(포털 개발 서버, /oauth2·/.well-known 은 IAM 으로 프록시)다.
 */
const state = JSON.parse(readFileSync(new URL('../.state/third-party.json', import.meta.url), 'utf8')) as {
  iam: string; portal: string; issuer: string; app: string; appPort: number;
  confidentialId: string; confidentialSecret: string; publicId: string;
  userEmail: string; userPassword: string; userName: string;
};
const ISSUER = state.issuer;
const REDIRECT = `${state.app}/callback`;

// ---------- 외부 앱(가짜) ----------
type Flow = { verifier: string; state: string; nonce: string; client: 'confidential' | 'public' };
type Result = { claims?: Record<string, unknown>; userinfo?: Record<string, unknown>; tokens?: Record<string, unknown>; error?: string; errorDescription?: string; state?: string };

class ExtApp {
  server!: Server;
  flows = new Map<string, Flow>();
  results = new Map<string, Result>();
  last: Result | undefined;
  private configs: Partial<Record<'confidential' | 'public', oidc.Configuration>> = {};

  async config(client: 'confidential' | 'public'): Promise<oidc.Configuration> {
    if (!this.configs[client]) {
      const opts = { execute: [oidc.allowInsecureRequests] };
      this.configs[client] = client === 'confidential'
        ? await oidc.discovery(new URL(ISSUER), state.confidentialId, state.confidentialSecret, undefined, opts)
        : await oidc.discovery(new URL(ISSUER), state.publicId, undefined, oidc.None(), opts);
    }
    return this.configs[client]!;
  }

  async start() {
    this.server = createServer(async (req, res) => {
      const url = new URL(req.url ?? '/', state.app);
      try {
        if (url.pathname === '/login') {
          const client = (url.searchParams.get('client') ?? 'confidential') as Flow['client'];
          const scope = url.searchParams.get('scope') ?? (client === 'confidential' ? 'openid profile email' : 'openid');
          const config = await this.config(client);
          const verifier = oidc.randomPKCECodeVerifier();
          const flow: Flow = { verifier, state: oidc.randomState(), nonce: oidc.randomNonce(), client };
          this.flows.set(flow.state, flow);
          const target = oidc.buildAuthorizationUrl(config, {
            redirect_uri: REDIRECT, scope, state: flow.state, nonce: flow.nonce,
            code_challenge: await oidc.calculatePKCECodeChallenge(verifier), code_challenge_method: 'S256',
          });
          res.writeHead(302, { Location: target.href }).end();
          return;
        }
        if (url.pathname === '/callback') {
          const st = url.searchParams.get('state') ?? '';
          const flow = this.flows.get(st);
          let result: Result;
          if (url.searchParams.get('error')) {
            result = { error: url.searchParams.get('error')!, errorDescription: url.searchParams.get('error_description') ?? undefined, state: st };
          } else if (!flow) {
            result = { error: 'unknown_state' };
          } else {
            const config = await this.config(flow.client);
            const tokens = await oidc.authorizationCodeGrant(config, new URL(req.url!, state.app), {
              pkceCodeVerifier: flow.verifier, expectedState: flow.state, expectedNonce: flow.nonce, idTokenExpected: true,
            });
            const claims = tokens.claims()!;
            const userinfo = await oidc.fetchUserInfo(config, tokens.access_token, claims.sub);
            result = { claims: claims as Record<string, unknown>, userinfo: userinfo as Record<string, unknown>, tokens: { ...tokens } as Record<string, unknown>, state: st };
          }
          this.last = result;
          this.results.set(st, result);
          res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
            .end(`<!doctype html><meta charset="utf-8"><title>외부 앱</title><h1 id="done">${result.error ? '실패' : '로그인 완료'}</h1><pre id="result">${JSON.stringify({ error: result.error, sub: result.claims?.sub })}</pre>`);
          return;
        }
        res.writeHead(404).end();
      } catch (e) {
        const msg = e instanceof Error ? `${e.name}: ${e.message}` : String(e);
        this.last = { error: `app_exception: ${msg}` };
        res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' }).end(msg);
      }
    });
    await new Promise<void>((ok) => this.server.listen(state.appPort, '127.0.0.1', ok));
  }

  async stop() { await new Promise<void>((ok) => this.server.close(() => ok())); }
}

// ---------- 도우미 ----------
const b64url = (b: Buffer) => b.toString('base64url');
const pkce = () => { const verifier = b64url(randomBytes(32)); return { verifier, challenge: b64url(createHash('sha256').update(verifier).digest()) }; };

async function portalLogin(page: Page) {
  await page.getByPlaceholder('user@doro.local').fill(state.userEmail);
  await page.getByRole('button', { name: '다음' }).click();
  await page.getByPlaceholder('••••••••').fill(state.userPassword);
  await page.getByRole('button', { name: '로그인', exact: true }).click();
}

async function userAccessToken(): Promise<string> {
  const res = await fetch(`${state.iam}/api/v1/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: state.userEmail, password: state.userPassword, deviceInfo: 'ext-e2e' }),
  });
  expect(res.status).toBe(200);
  return ((await res.json()) as { data: { tokens: { accessToken: string } } }).data.tokens.accessToken;
}

/** 포털의 동의 화면이 하는 일과 같다: 로그인한 사용자의 Bearer 로 인가 엔드포인트를 불러 코드를 받는다. */
async function issueCode(clientId: string, challenge: string, over: Record<string, string> = {}): Promise<string> {
  const params = new URLSearchParams({ client_id: clientId, redirect_uri: REDIRECT, response_type: 'code', scope: 'openid', state: 's1', code_challenge: challenge, code_challenge_method: 'S256', ...over });
  const res = await fetch(`${state.iam}/oauth2/authorize?${params}`, { headers: { Authorization: `Bearer ${await userAccessToken()}` } });
  expect(res.status, await res.clone().text()).toBe(200);
  return ((await res.json()) as { data: { code: string } }).data.code;
}

async function tokenRequest(form: Record<string, string>, auth?: { id: string; secret: string; mode: 'basic' | 'post' }) {
  const headers: Record<string, string> = { 'Content-Type': 'application/x-www-form-urlencoded' };
  const body = new URLSearchParams(form);
  if (auth?.mode === 'basic') headers.Authorization = `Basic ${Buffer.from(`${auth.id}:${auth.secret}`).toString('base64')}`;
  if (auth?.mode === 'post') { body.set('client_id', auth.id); body.set('client_secret', auth.secret); }
  const res = await fetch(`${state.iam}/oauth2/token`, { method: 'POST', headers, body });
  return { status: res.status, json: (await res.json().catch(() => ({}))) as Record<string, any> };
}

/** 브라우저 이동 흐름의 인가 요청(리다이렉트를 따라가지 않고 응답만 본다). */
async function browserAuthorize(params: Record<string, string>) {
  const q = new URLSearchParams({ response_type: 'code', redirect_uri: REDIRECT, state: 'st-1', ...params });
  const res = await fetch(`${ISSUER}/oauth2/authorize?${q}`, { redirect: 'manual' });
  return { status: res.status, location: res.headers.get('location'), text: res.status >= 300 && res.status < 400 ? '' : await res.text() };
}

const app = new ExtApp();
test.beforeAll(async () => { await app.start(); });
test.afterAll(async () => { await app.stop(); });

// ---------- 시험 ----------
test.describe('발견 문서', () => {
  test('표준 OIDC 클라이언트가 발견 문서만으로 설정할 수 있다', async () => {
    const res = await fetch(`${ISSUER}/.well-known/openid-configuration`);
    expect(res.status).toBe(200);
    const doc = (await res.json()) as Record<string, any>;
    expect(doc.issuer).toBe(ISSUER);
    for (const k of ['authorization_endpoint', 'token_endpoint', 'userinfo_endpoint', 'jwks_uri']) {
      expect(String(doc[k]).startsWith(ISSUER), `${k} 가 발급자 주소 아래에 있어야 한다: ${doc[k]}`).toBe(true);
    }
    expect(doc.code_challenge_methods_supported).toEqual(['S256']);
    expect(doc.id_token_signing_alg_values_supported).toContain('RS256');
    const jwks = (await (await fetch(doc.jwks_uri)).json()) as { keys: Array<Record<string, string>> };
    expect(jwks.keys.length).toBeGreaterThan(0);
    expect(jwks.keys.every((k) => k.n && k.e && !('d' in k)), '공개키만 있어야 한다').toBe(true);
  });
});

test.describe('기밀 클라이언트: 로그인 → 동의 → 토큰 → 검증', () => {
  test('끝까지 된다(id_token 은 라이브러리가 서명·iss·aud·nonce 를 검증, userinfo 일치, 갱신, 폐기)', async ({ page }) => {
    await page.goto(`${state.app}/login?client=confidential`);
    // 로그인 전이라 포털 로그인 화면으로 보내졌다가, 로그인하면 동의 화면으로 돌아온다
    await portalLogin(page);
    await expect(page.getByText('다음 권한을 요청합니다')).toBeVisible();
    await expect(page.getByText('시험 외부 앱').first(), '동의 화면은 등록된 앱 이름을 보여 줘야 한다(client_id 문자열이 아니라)').toBeVisible();
    await page.getByRole('button', { name: /계속/ }).click();
    await expect(page.locator('#done')).toHaveText('로그인 완료');

    const r = app.last!;
    expect(r.error).toBeUndefined();
    const c = r.claims!;
    expect(c.iss).toBe(ISSUER);
    expect([c.aud].flat()).toEqual([state.confidentialId]);
    expect(typeof c.sub).toBe('string');
    expect(c.email).toBe(state.userEmail);
    expect(c.name).toBe(state.userName);
    expect(r.userinfo!.sub).toBe(c.sub);
    expect(r.userinfo!.email).toBe(state.userEmail);
    const t = r.tokens!;
    expect(t.token_type?.toString().toLowerCase()).toBe('bearer');
    expect(typeof t.refresh_token).toBe('string');

    // 갱신: 새 토큰이 나온다
    const cfg = await app.config('confidential');
    const refreshed = await oidc.refreshTokenGrant(cfg, t.refresh_token as string);
    expect(refreshed.access_token).toBeTruthy();
    expect(refreshed.refresh_token, '리프레시 토큰은 쓸 때마다 새것으로 교체된다(재사용 탐지)').toBeTruthy();
    expect(refreshed.refresh_token).not.toBe(t.refresh_token);
    const info = await oidc.fetchUserInfo(cfg, refreshed.access_token, c.sub as string);
    expect(info.sub).toBe(c.sub);
    expect(info.email, '갱신 뒤에도 처음 허용한 범위(email 포함)가 이어진다').toBe(state.userEmail);

    // 폐기: 이후 그 리프레시 토큰은 쓸 수 없다
    const latest = (refreshed.refresh_token ?? t.refresh_token) as string;
    const rev = await fetch(`${state.iam}/oauth2/revoke`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', Authorization: `Basic ${Buffer.from(`${state.confidentialId}:${state.confidentialSecret}`).toString('base64')}` },
      body: new URLSearchParams({ token: latest }),
    });
    expect(rev.status).toBe(200);
    await expect(oidc.refreshTokenGrant(cfg, latest)).rejects.toThrow();
  });

  test('openid 만 요청하면 이메일·이름이 나오지 않는다(scope 최소화)', async ({ page }) => {
    await page.goto(`${state.app}/login?client=confidential&scope=openid`);
    // 이전 시험에서 이미 로그인돼 있을 수 있어 둘 다 처리한다
    if (await page.getByPlaceholder('user@doro.local').isVisible().catch(() => false)) await portalLogin(page);
    await page.getByRole('button', { name: /계속/ }).click();
    await expect(page.locator('#done')).toHaveText('로그인 완료');
    const r = app.last!;
    expect(r.claims!.email, 'email scope 없이 이메일이 나오면 안 된다').toBeUndefined();
    expect(r.userinfo!.email).toBeUndefined();
    expect(r.claims!.sub).toBeTruthy();
  });
});

test.describe('공개 클라이언트(비밀키 없음, PKCE 만)', () => {
  test('PKCE 로 로그인이 된다', async ({ page }) => {
    await page.goto(`${state.app}/login?client=public`);
    if (await page.getByPlaceholder('user@doro.local').isVisible().catch(() => false)) await portalLogin(page);
    await page.getByRole('button', { name: /계속/ }).click();
    await expect(page.locator('#done')).toHaveText('로그인 완료');
    expect([app.last!.claims!.aud].flat()).toEqual([state.publicId]);
  });
});

test.describe('사용자가 거부하면', () => {
  test('앱으로 error=access_denied 와 state 가 돌아간다(RFC 6749 §4.1.2.1)', async ({ page }) => {
    await page.goto(`${state.app}/login?client=confidential`);
    if (await page.getByPlaceholder('user@doro.local').isVisible().catch(() => false)) await portalLogin(page);
    await page.getByRole('button', { name: '취소' }).click();
    await expect(page.locator('#done')).toHaveText('실패');
    expect(app.last!.error).toBe('access_denied');
    expect(app.last!.state, '거부 응답에도 state 가 그대로 돌아와야 앱이 요청과 맞춰 볼 수 있다').toBeTruthy();
  });
});

test.describe('잘못된 요청은 안전하게 거부된다', () => {
  const ch = pkce().challenge;

  test('등록되지 않은 redirect_uri 는 리다이렉트하지 않는다(오픈 리다이렉트 방지)', async () => {
    const r = await browserAuthorize({ client_id: state.confidentialId, redirect_uri: 'https://evil.example/cb', scope: 'openid', code_challenge: ch, code_challenge_method: 'S256' });
    expect(r.status).toBe(400);
    expect(r.location).toBeNull();
  });

  test('모르는 클라이언트는 리다이렉트하지 않는다', async () => {
    const r = await browserAuthorize({ client_id: 'nobody', scope: 'openid', code_challenge: ch, code_challenge_method: 'S256' });
    expect(r.status).toBeGreaterThanOrEqual(400);
    expect(r.location).toBeNull();
  });

  for (const [name, extra, code] of [
    ['PKCE 없음', { code_challenge: '', code_challenge_method: '' }, 'invalid_request'],
    ['PKCE plain 거부', { code_challenge_method: 'plain' }, 'invalid_request'],
    ['response_type=token(암시적 허용 안 함)', { response_type: 'token' }, 'unsupported_response_type'],
    ['허용되지 않은 scope(admin)', { scope: 'openid admin' }, 'invalid_scope'],
  ] as const) {
    test(`${name} → redirect_uri 로 ${code} 와 state 가 돌아간다`, async () => {
      const r = await browserAuthorize({ client_id: state.confidentialId, scope: 'openid', code_challenge: ch, code_challenge_method: 'S256', ...extra });
      expect(r.status).toBe(302);
      const loc = new URL(r.location!);
      expect(`${loc.origin}${loc.pathname}`).toBe(REDIRECT);
      expect(loc.searchParams.get('error')).toBe(code);
      expect(loc.searchParams.get('state')).toBe('st-1');
    });
  }

  test('공개 클라이언트가 허용 밖 scope(email)를 요청하면 거부된다', async () => {
    const r = await browserAuthorize({ client_id: state.publicId, scope: 'openid email', code_challenge: ch, code_challenge_method: 'S256' });
    expect(r.status).toBe(302);
    expect(new URL(r.location!).searchParams.get('error')).toBe('invalid_scope');
  });
});

test.describe('인가 코드와 토큰 요청', () => {
  const basic = () => ({ id: state.confidentialId, secret: state.confidentialSecret, mode: 'basic' as const });

  test('코드는 한 번만 쓸 수 있고, 두 번째는 invalid_grant 다', async () => {
    const { verifier, challenge } = pkce();
    const code = await issueCode(state.confidentialId, challenge);
    const form = { grant_type: 'authorization_code', code, redirect_uri: REDIRECT, code_verifier: verifier };
    const first = await tokenRequest(form, basic());
    expect(first.status, JSON.stringify(first.json)).toBe(200);
    const second = await tokenRequest(form, basic());
    expect(second.status).toBe(400);
    expect(second.json.error).toBe('invalid_grant');
  });

  test('code_verifier 가 틀리면 invalid_grant 다', async () => {
    const { challenge } = pkce();
    const code = await issueCode(state.confidentialId, challenge);
    const r = await tokenRequest({ grant_type: 'authorization_code', code, redirect_uri: REDIRECT, code_verifier: b64url(randomBytes(32)) }, basic());
    expect(r.status).toBe(400);
    expect(r.json.error).toBe('invalid_grant');
  });

  test('client_secret 이 틀리면 invalid_client(401) 다', async () => {
    const { verifier, challenge } = pkce();
    const code = await issueCode(state.confidentialId, challenge);
    const r = await tokenRequest({ grant_type: 'authorization_code', code, redirect_uri: REDIRECT, code_verifier: verifier }, { id: state.confidentialId, secret: 'wrong-secret', mode: 'basic' });
    expect(r.status).toBe(401);
    expect(r.json.error).toBe('invalid_client');
  });

  test('다른 클라이언트가 남의 코드를 쓰려 하면 거부된다', async () => {
    const { verifier, challenge } = pkce();
    const code = await issueCode(state.confidentialId, challenge);
    const r = await tokenRequest({ grant_type: 'authorization_code', code, redirect_uri: REDIRECT, code_verifier: verifier, client_id: state.publicId });
    expect(r.status).toBeGreaterThanOrEqual(400);
    expect(r.json.access_token).toBeUndefined();
  });

  test('client_secret_post 방식도 된다', async () => {
    const { verifier, challenge } = pkce();
    const code = await issueCode(state.confidentialId, challenge);
    const r = await tokenRequest({ grant_type: 'authorization_code', code, redirect_uri: REDIRECT, code_verifier: verifier }, { id: state.confidentialId, secret: state.confidentialSecret, mode: 'post' });
    expect(r.status, JSON.stringify(r.json)).toBe(200);
  });
});

test.describe('외부 앱 토큰의 권한 범위', () => {
  test('외부 앱이 받은 액세스 토큰으로는 userinfo 만 되고 IAM 의 다른 API 는 거부된다', async () => {
    const { verifier, challenge } = pkce();
    const code = await issueCode(state.confidentialId, challenge, { scope: 'openid profile email' });
    const tok = await tokenRequest({ grant_type: 'authorization_code', code, redirect_uri: REDIRECT, code_verifier: verifier }, { id: state.confidentialId, secret: state.confidentialSecret, mode: 'basic' });
    expect(tok.status).toBe(200);
    const bearer = { Authorization: `Bearer ${tok.json.access_token}` };
    expect((await fetch(`${state.iam}/oauth2/userinfo`, { headers: bearer })).status).toBe(200);
    for (const [method, path] of [['GET', '/api/v1/sessions'], ['GET', '/api/v1/admin/users'], ['POST', '/api/v1/auth/logout']] as const) {
      const res = await fetch(`${state.iam}${path}`, { method, headers: bearer });
      expect([401, 403], `${method} ${path} 가 외부 앱 토큰을 받아들였다: ${res.status}`).toContain(res.status);
    }
  });
});

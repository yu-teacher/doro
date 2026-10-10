import type { Page, Route } from '@playwright/test';

export type Role = 'USER' | 'ADMIN' | 'SUPER_ADMIN';

const ONE_HOUR_SECONDS = 3600;
const LONG_UNBROKEN = 'x'.repeat(64);

export function fakeJwt(payload: Record<string, unknown>): string {
  const encode = (value: unknown) => Buffer.from(JSON.stringify(value)).toString('base64url');
  return `${encode({ alg: 'RS256' })}.${encode({ exp: Math.floor(Date.now() / 1000) + ONE_HOUR_SECONDS, ...payload })}.sig`;
}

export interface FakeAccount {
  userId: string;
  email: string;
  fullName: string;
  role: Role;
}

export const ADMIN_ACCOUNT: FakeAccount = { userId: 'u-admin', email: 'admin@doro.test', fullName: '관리자', role: 'ADMIN' };
export const USER_ACCOUNT: FakeAccount = { userId: 'u-user', email: 'user@doro.test', fullName: '일반 사용자', role: 'USER' };

/** 로그인된 상태로 시작한다: 저장소에는 계정 목록만 있고(토큰 없음), 첫 요청이 모의 갱신 API 로 토큰을 받는다(실제 앱과 같은 경로). */
export async function seedLoggedIn(page: Page, account: FakeAccount): Promise<void> {
  await page.addInitScript((a) => {
    localStorage.setItem('doro_auth_accounts', JSON.stringify([{ ...a, slot: 0, sessionId: 's-1', userIndex: 0, profileImageUrl: null }]));
    localStorage.setItem('doro_active_account_index', '0');
  }, account);
}

function profileOf(account: FakeAccount) {
  return {
    id: account.userId, email: account.email, name: account.fullName, profileImageUrl: null, status: 'ACTIVE',
    role: account.role, hasTotp: false, createdAt: '2026-01-01T00:00:00Z',
  };
}

/** 관리자 화면에 쓰는 사용자 목록: 긴 이름·긴 이메일·정지 사유·잠김 등 레이아웃을 깨기 쉬운 값을 섞는다. */
function adminUsers(me: FakeAccount) {
  const base = { profileImageUrl: null, hasTotp: false, createdAt: '2026-02-01T00:00:00Z' };
  return [
    profileOf(me),
    { ...base, id: 'u1', email: 'normal@doro.test', name: '일반회원', status: 'ACTIVE', role: 'USER' },
    { ...base, id: 'u2', email: `${LONG_UNBROKEN}@doro.test`, name: `이름이아주길어서줄바꿈이필요한사용자${LONG_UNBROKEN}`, status: 'ACTIVE', role: 'USER', hasTotp: true },
    { ...base, id: 'u3', email: 'suspended@doro.test', name: '정지된회원', status: 'SUSPENDED', role: 'USER', suspendedAt: '2026-10-01T00:00:00Z', suspensionReason: `스팸 게시물 반복 등록 ${LONG_UNBROKEN}` },
    { ...base, id: 'u4', email: 'locked@doro.test', name: '잠긴회원', status: 'ACTIVE', role: 'USER', locked: true },
    { ...base, id: 'u5', email: 'other-admin@doro.test', name: '다른관리자', status: 'ACTIVE', role: 'ADMIN' },
  ];
}

function logLines() {
  const nowNs = BigInt(Date.now()) * 1_000_000n;
  const line = (offset: number, level: string, message: string) => [
    String(nowNs - BigInt(offset) * 1_000_000_000n),
    `2026-10-10 12:00:00.000 [http-nio-8080-exec-1] ${level} c.h.a.SomeLongLoggerName [traceId=${LONG_UNBROKEN.slice(0, 32)}] [userId=u1] [clientIp=121.132.0.39] - ${message}`,
  ];
  return [
    { stream: { service: 'auth' }, values: [line(1, 'ERROR', `AuthException [INVALID_CREDENTIALS]: ${LONG_UNBROKEN}${LONG_UNBROKEN}`), line(2, 'WARN', '로그인 실패 5회로 계정 잠금'), line(3, 'INFO', 'Started AuthApplication')] },
    { stream: { service: 'blog' }, values: [line(4, 'INFO', '글 저장 완료')] },
  ];
}

export interface MockApiOptions {
  /** 로그인된 계정(없으면 비로그인) */
  account?: FakeAccount;
}

const json = (route: Route, body: unknown, status = 200) =>
  route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });

/** 포털이 부르는 API 를 가짜 응답으로 대체한다. 모르는 API 는 404 로 둬서 예상치 못한 호출을 드러낸다. */
export async function mockApi(page: Page, options: MockApiOptions = {}): Promise<void> {
  const me = options.account ?? USER_ACCOUNT;
  // 개발 서버가 내려 주는 소스(/src/api/...)와 겹치지 않도록 주소의 맨 앞 경로로만 판단한다
  await page.route((url) => /^\/(api\/v1|oauth2|loki)\//.test(url.pathname), async (route) => {
    const request = route.request();
    // 화면 이동(/oauth2/consent 등)은 개발 서버가 내려 주는 페이지다. 데이터 요청(xhr/fetch)만 가짜로 응답한다.
    if (!['xhr', 'fetch'].includes(request.resourceType())) return route.fallback();
    const { pathname } = new URL(request.url());
    const method = request.method();

    if (pathname === '/api/v1/auth/lookup' && method === 'POST') {
      return json(route, { success: true, data: { email: (request.postDataJSON() as { email: string }).email, name: '테스트 사용자', profileImageUrl: null } });
    }
    if (pathname === '/api/v1/auth/token/refresh' && method === 'POST') {
      return json(route, { success: true, data: { accessToken: fakeJwt({ sub: me.userId, role: me.role, sid: 's-1' }), tokenType: 'Bearer', expiresIn: 900, sessionId: 's-1', userIndex: 0 } });
    }
    if (pathname === '/api/v1/users/me' && method === 'GET') return json(route, { success: true, data: profileOf(me) });
    if (pathname === '/api/v1/sessions' && method === 'GET') {
      const now = new Date().toISOString();
      return json(route, {
        success: true,
        data: [
          { sessionId: 's-1', userId: me.userId, userIndex: 0, deviceInfo: 'Chrome on Android (현재 기기)', ipAddress: '121.132.0.39', isActive: true, lastActiveAt: now, expiresAt: now, createdAt: now },
          { sessionId: 's-2', userId: me.userId, userIndex: 1, deviceInfo: `Safari on iPhone ${LONG_UNBROKEN}`, ipAddress: '2001:0db8:85a3:0000:0000:8a2e:0370:7334', isActive: true, lastActiveAt: now, expiresAt: now, createdAt: now },
        ],
      });
    }
    if (pathname === '/api/v1/admin/users' && method === 'GET') return json(route, { success: true, data: adminUsers(me) });
    if (pathname === '/oauth2/client-info') return json(route, { success: true, data: { firstParty: false } });
    if (pathname.startsWith('/loki/api/v1/query_range')) return json(route, { status: 'success', data: { resultType: 'streams', result: logLines() } });
    if (pathname.startsWith('/loki/api/v1/label/service/values')) return json(route, { status: 'success', data: ['auth', 'blog', 'guard', 'party', 'games'] });
    return json(route, { success: false, code: 'NOT_MOCKED', message: `${method} ${pathname}` }, 404);
  });
}

export const LONG_VALUE = LONG_UNBROKEN;

/**
 * 앱 안에서 화면을 이동한다. 개발 서버는 /oauth2/* 주소를 IAM 으로 넘기므로(프록시) 그 주소를 주소창에서 직접 열면 앱이 아니라 IAM 의 응답이 온다.
 * 운영에서는 게이트웨이가 /oauth2/consent 를 포털로 보내므로 앱 안에서 그 주소로 이동한 것과 같다.
 */
export async function gotoInApp(page: Page, path: string): Promise<void> {
  await page.goto('/');
  await page.evaluate((target) => {
    history.pushState({}, '', target);
    dispatchEvent(new PopStateEvent('popstate'));
  }, path);
}

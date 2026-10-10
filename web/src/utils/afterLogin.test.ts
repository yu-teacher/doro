import { afterEach, describe, expect, it } from 'vitest';
import { saveConsentReturn, clearConsentReturn } from './consentReturn';
import { DEFAULT_AFTER_LOGIN_PATH, loginStateFrom, readReturnPath, resolveAfterLoginPath } from './afterLogin';

const CONSENT_QUERY = new URLSearchParams({
  client_id: 'doro-blog',
  redirect_uri: 'https://varen05.asuscomm.com/blog/bff/callback',
  code_challenge: 'x'.repeat(43),
  code_challenge_method: 'S256',
  state: 'abc',
});

describe('로그인 뒤 이동 경로', () => {
  afterEach(() => {
    clearConsentReturn();
    sessionStorage.clear();
  });

  it('보던 포털 화면은 돌아올 곳으로 넘기고, 로그인·가입·동의 화면과 모르는 경로는 넘기지 않는다', () => {
    expect(loginStateFrom({ pathname: '/' })).toEqual({ from: '/' });
    expect(loginStateFrom({ pathname: '/account' })).toEqual({ from: '/account' });
    expect(loginStateFrom({ pathname: '/logs' })).toEqual({ from: '/logs' });
    for (const path of ['/login', '/signup', '/oauth2/consent', '/anything-else']) {
      expect(loginStateFrom({ pathname: path })).toBeUndefined();
    }
  });

  it('돌아갈 경로는 허용된 포털 경로만 인정한다(외부 주소·프로토콜 상대 주소·로그인 화면은 무시)', () => {
    expect(readReturnPath({ from: '/logs' })).toBe('/logs');
    for (const from of ['https://evil.example/', '//evil.example', '/\\evil', 'javascript:alert(1)', '/login', '/signup', '/account/../x', '', 5, null]) {
      expect(readReturnPath({ from })).toBeNull();
    }
    expect(readReturnPath(undefined)).toBeNull();
    expect(readReturnPath('/logs')).toBeNull();
    expect(readReturnPath({})).toBeNull();
  });

  it('우선순위: 서비스 로그인 요청(동의) > 보던 화면 > 내 계정', () => {
    expect(resolveAfterLoginPath(undefined)).toBe(DEFAULT_AFTER_LOGIN_PATH);
    expect(resolveAfterLoginPath({ from: '/logs' })).toBe('/logs');
    expect(saveConsentReturn(CONSENT_QUERY)).toBe(true);
    const path = resolveAfterLoginPath({ from: '/logs' });
    expect(path.startsWith('/oauth2/consent?')).toBe(true);
    expect(path).toContain('client_id=doro-blog');
  });
});

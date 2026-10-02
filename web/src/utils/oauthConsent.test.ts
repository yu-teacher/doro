import { describe, expect, it } from 'vitest';
import {
  buildAuthorizationRedirect,
  buildAuthorizeParams,
  describeScopes,
  parseConsentRequest,
  resolveAuthorizeError,
  sanitizeServerMessage,
} from './oauthConsent';
import type { ConsentRequest } from './oauthConsent';

const CHALLENGE = 'E9Melhoa2OwvFrGMTJguCH5rtx64FIbEIqiPQsjzkxo';

function parse(overrides: Record<string, string | null>) {
  const params = new URLSearchParams({ client_id: 'doro-docs', redirect_uri: 'https://docs.example.com/cb', code_challenge: CHALLENGE });
  Object.entries(overrides).forEach(([key, value]) => (value === null ? params.delete(key) : params.set(key, value)));
  return parseConsentRequest(params);
}

describe('parseConsentRequest', () => {
  it('정상 요청을 파싱하고 표시용 호스트를 계산한다', () => {
    const result = parse({ state: 'abc' });
    expect(result).toMatchObject({ ok: true, request: { clientId: 'doro-docs', redirectHost: 'docs.example.com', state: 'abc' } });
  });

  it.each(['client_id', 'redirect_uri', 'code_challenge'])('%s 가 없으면 거부한다', (key) => {
    expect(parse({ [key]: null }).ok).toBe(false);
  });

  it.each([
    'javascript:alert(1)',
    'data:text/html,<script>alert(1)</script>',
    'ftp://docs.example.com/cb',
    'http://docs.example.com/cb',
    'http://localhost.evil.example/cb',
    'https://user:pass@docs.example.com/cb',
    'https://docs.example.com/cb#fragment',
    '//evil.example/cb',
    'not a url',
  ])('위험하거나 잘못된 redirect_uri(%s)는 거부한다', (redirectUri) => {
    expect(parse({ redirect_uri: redirectUri }).ok).toBe(false);
  });

  it('형식이 틀린 code_challenge 는 거부한다', () => {
    expect(parse({ code_challenge: 'short' }).ok).toBe(false);
  });

  it.each(['http://localhost:3000/cb', 'http://127.0.0.1:8080/cb', 'http://[::1]:8080/cb'])(
    'loopback 호스트의 http redirect_uri(%s)는 허용한다',
    (redirectUri) => {
      expect(parse({ redirect_uri: redirectUri }).ok).toBe(true);
    },
  );

  it('code_challenge_method 는 없거나 S256 만 허용한다', () => {
    expect(parse({ code_challenge_method: 'S256' }).ok).toBe(true);
    expect(parse({}).ok).toBe(true);
    expect(parse({ code_challenge_method: 'plain' }).ok).toBe(false);
    expect(parse({ code_challenge_method: 's256' }).ok).toBe(false);
  });

  it('scope 를 파싱하고(중복 제거) 지원하지 않는 값·잘못된 형식은 거부한다', () => {
    expect(parse({ scope: 'openid profile email openid' })).toMatchObject({ ok: true, request: { scopes: ['openid', 'profile', 'email'] } });
    expect(parse({})).toMatchObject({ ok: true, request: { scopes: [] } });
    expect(parse({ scope: '   ' })).toMatchObject({ ok: true, request: { scopes: [] } });
    expect(parse({ scope: 'openid admin' }).ok).toBe(false);
    expect(parse({ scope: 'toString' }).ok).toBe(false);
    expect(parse({ scope: 'openid"x' }).ok).toBe(false);
    expect(parse({ scope: 'openid ' + 'a'.repeat(200) }).ok).toBe(false);
  });

  it('nonce 를 읽고 너무 긴 state/nonce 는 거부한다', () => {
    expect(parse({ nonce: 'n-1' })).toMatchObject({ ok: true, request: { nonce: 'n-1' } });
    expect(parse({})).toMatchObject({ ok: true, request: { nonce: null, state: null } });
    expect(parse({ state: 's'.repeat(513) }).ok).toBe(false);
    expect(parse({ nonce: 'n'.repeat(257) }).ok).toBe(false);
  });
});

describe('describeScopes', () => {
  it('스코프를 한국어 설명으로 바꾼다', () => {
    const labels = describeScopes(['openid', 'profile', 'email']).map((d) => d.label);
    expect(labels).toHaveLength(3);
    expect(labels.join(' ')).toContain('이메일');
    expect(labels.join(' ')).toContain('프로필');
  });

  it('스코프가 없으면 기본 로그인 설명 한 줄을 돌려준다', () => {
    expect(describeScopes([])).toHaveLength(1);
  });
});

describe('buildAuthorizeParams', () => {
  const base: ConsentRequest = {
    clientId: 'doro-docs',
    redirectUri: 'https://docs.example.com/cb',
    redirectHost: 'docs.example.com',
    codeChallenge: CHALLENGE,
    codeChallengeMethod: 'S256',
    scopes: [],
    state: null,
    nonce: null,
  };

  it('필수 파라미터만 담고 비어 있는 선택 파라미터는 생략한다', () => {
    expect(buildAuthorizeParams(base)).toEqual({
      client_id: 'doro-docs',
      redirect_uri: 'https://docs.example.com/cb',
      response_type: 'code',
      code_challenge: CHALLENGE,
      code_challenge_method: 'S256',
    });
  });

  it('scope(공백 구분)/state/nonce 를 그대로 전달한다', () => {
    expect(buildAuthorizeParams({ ...base, scopes: ['openid', 'email'], state: 'st', nonce: 'nn' })).toMatchObject({
      scope: 'openid email',
      state: 'st',
      nonce: 'nn',
    });
  });
});

describe('sanitizeServerMessage / resolveAuthorizeError', () => {
  it('문자열이 아니거나 비어 있으면 null', () => {
    expect(sanitizeServerMessage(undefined)).toBeNull();
    expect(sanitizeServerMessage({ message: 'x' })).toBeNull();
    expect(sanitizeServerMessage('  \n\t ')).toBeNull();
  });

  it('제어 문자를 한 줄로 정리하고 길이를 제한한다', () => {
    expect(sanitizeServerMessage('a\r\nb\u0000c')).toBe('a b c');
    const long = sanitizeServerMessage('x'.repeat(500));
    expect(long).not.toBeNull();
    expect(long!.length).toBeLessThanOrEqual(201);
  });

  it('HTML 조각은 문자열 그대로 남긴다(렌더링은 React 텍스트 이스케이프가 담당)', () => {
    expect(sanitizeServerMessage('<img src=x onerror=alert(1)>')).toBe('<img src=x onerror=alert(1)>');
  });

  it('400 이면 서버 사유를 덧붙이고, 그 외 상태는 일반 안내만 보여 준다', () => {
    expect(resolveAuthorizeError(400, '허용되지 않은 redirect_uri 입니다.')).toContain('허용되지 않은 redirect_uri 입니다.');
    expect(resolveAuthorizeError(400, undefined)).toContain('허용되지 않은 요청');
    expect(resolveAuthorizeError(500, 'internal stack trace')).not.toContain('stack trace');
    expect(resolveAuthorizeError(undefined, 'ignored')).not.toContain('ignored');
  });
});

describe('buildAuthorizationRedirect', () => {
  it('서버가 발급한 코드와 state 를 붙이고 기존 쿼리는 보존한다', () => {
    expect(buildAuthorizationRedirect('https://docs.example.com/cb?tenant=a', 'CODE', 'st'))
      .toBe('https://docs.example.com/cb?tenant=a&code=CODE&state=st');
  });

  it('state 가 없으면 붙이지 않는다', () => {
    expect(buildAuthorizationRedirect('https://docs.example.com/cb', 'CODE', null))
      .toBe('https://docs.example.com/cb?code=CODE');
  });
});

import { describe, expect, it } from 'vitest';
import { buildAuthorizationRedirect, parseConsentRequest } from './oauthConsent';

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

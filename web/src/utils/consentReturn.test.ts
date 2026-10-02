import { beforeEach, describe, expect, it } from 'vitest';
import {
  CONSENT_RETURN_STORAGE_KEY,
  CONSENT_RETURN_TTL_MS,
  clearConsentReturn,
  peekConsentReturnPath,
  saveConsentReturn,
} from './consentReturn';

const CHALLENGE = 'A'.repeat(43);

function params(overrides: Record<string, string> = {}): URLSearchParams {
  return new URLSearchParams({
    client_id: 'doro-blog',
    redirect_uri: 'https://blog.example.com/callback?x=1',
    code_challenge: CHALLENGE,
    state: 'st',
    ...overrides,
  });
}

describe('consentReturn', () => {
  beforeEach(() => {
    sessionStorage.clear();
  });

  it('검증된 동의 요청을 저장하고 상대 경로로 복원한다', () => {
    expect(saveConsentReturn(params(), 1000)).toBe(true);
    const path = peekConsentReturnPath(2000);
    expect(path).not.toBeNull();
    expect(path!.startsWith('/oauth2/consent?')).toBe(true);
    const restored = new URLSearchParams(path!.split('?')[1]);
    expect(restored.get('client_id')).toBe('doro-blog');
    expect(restored.get('redirect_uri')).toBe('https://blog.example.com/callback?x=1');
    expect(restored.get('code_challenge')).toBe(CHALLENGE);
    expect(restored.get('state')).toBe('st');
  });

  it('scope/nonce/code_challenge_method 도 함께 저장·복원한다', () => {
    saveConsentReturn(params({ scope: 'openid email', nonce: 'nn', code_challenge_method: 'S256' }), 1000);
    const restored = new URLSearchParams(peekConsentReturnPath(1500)!.split('?')[1]);
    expect(restored.get('scope')).toBe('openid email');
    expect(restored.get('nonce')).toBe('nn');
    expect(restored.get('code_challenge_method')).toBe('S256');
  });

  it('검증에 실패한 요청은 저장하지 않는다', () => {
    expect(saveConsentReturn(params({ code_challenge: 'short' }))).toBe(false);
    expect(saveConsentReturn(params({ redirect_uri: 'javascript:alert(1)' }))).toBe(false);
    expect(sessionStorage.getItem(CONSENT_RETURN_STORAGE_KEY)).toBeNull();
  });

  it('허용된 파라미터만 저장하고 임의의 return URL 같은 값은 버린다', () => {
    saveConsentReturn(params({ return_to: 'https://evil.example.com', next: '//evil.example.com' }), 1000);
    const path = peekConsentReturnPath(1500)!;
    expect(path).not.toContain('evil');
    expect(path.startsWith('/oauth2/consent?')).toBe(true);
  });

  it('TTL 이 지나면 null 을 반환하고 저장값을 지운다', () => {
    saveConsentReturn(params(), 1000);
    expect(peekConsentReturnPath(1000 + CONSENT_RETURN_TTL_MS + 1)).toBeNull();
    expect(sessionStorage.getItem(CONSENT_RETURN_STORAGE_KEY)).toBeNull();
  });

  it('저장값이 손상되었거나 변조되어 검증에 실패하면 null 이고 지운다', () => {
    sessionStorage.setItem(CONSENT_RETURN_STORAGE_KEY, '{not json');
    expect(peekConsentReturnPath()).toBeNull();
    expect(sessionStorage.getItem(CONSENT_RETURN_STORAGE_KEY)).toBeNull();

    sessionStorage.setItem(
      CONSENT_RETURN_STORAGE_KEY,
      JSON.stringify({ query: 'client_id=a&redirect_uri=javascript:alert(1)&code_challenge=x', savedAt: Date.now() }),
    );
    expect(peekConsentReturnPath()).toBeNull();
    expect(sessionStorage.getItem(CONSENT_RETURN_STORAGE_KEY)).toBeNull();
  });

  it('clearConsentReturn 은 저장값을 지운다', () => {
    saveConsentReturn(params());
    clearConsentReturn();
    expect(peekConsentReturnPath()).toBeNull();
  });
});

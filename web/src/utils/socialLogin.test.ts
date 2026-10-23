import { describe, expect, it } from 'vitest';
import { parseSocialReturn, socialErrorMessage, socialStartUrl } from './socialLogin';

const parse = (query: string) => parseSocialReturn(new URLSearchParams(query));

describe('parseSocialReturn', () => {
  it('소셜 로그인으로 돌아온 것이 아니면 null', () => {
    expect(parse('')).toBeNull();
    expect(parse('foo=bar')).toBeNull();
  });

  it('성공은 슬롯을 돌려준다', () => {
    expect(parse('social=ok&slot=2')).toEqual({ kind: 'ok', slot: 2 });
  });

  it('슬롯이 없거나 범위 밖이면 오류로 본다', () => {
    expect(parse('social=ok')).toEqual({ kind: 'error', code: 'provider_error' });
    expect(parse('social=ok&slot=5')).toEqual({ kind: 'error', code: 'provider_error' });
    expect(parse('social=ok&slot=-1')).toEqual({ kind: 'error', code: 'provider_error' });
    expect(parse('social=ok&slot=1x')).toEqual({ kind: 'error', code: 'provider_error' });
  });

  it('2FA 는 티켓과 슬롯이 모두 있어야 한다', () => {
    expect(parse('social=2fa&slot=4&ticket=abc')).toEqual({ kind: '2fa', slot: 4, ticket: 'abc' });
    expect(parse('social=2fa&slot=4')).toEqual({ kind: 'error', code: 'provider_error' });
    expect(parse('social=2fa&ticket=abc')).toEqual({ kind: 'error', code: 'provider_error' });
  });

  it('오류는 코드를 그대로 넘기고, 코드가 없으면 일반 오류', () => {
    expect(parse('social=error&code=denied')).toEqual({ kind: 'error', code: 'denied' });
    expect(parse('social=error')).toEqual({ kind: 'error', code: 'provider_error' });
    expect(parse('social=whatever')).toEqual({ kind: 'error', code: 'provider_error' });
  });
});

describe('socialErrorMessage', () => {
  it('알려진 코드는 문구로 바꾸고 모르는 코드는 일반 문구', () => {
    expect(socialErrorMessage('email_not_verified')).toContain('이메일');
    expect(socialErrorMessage('denied')).toContain('취소');
    expect(socialErrorMessage('<script>')).toBe(socialErrorMessage('provider_error'));
  });
});

describe('socialStartUrl', () => {
  it('공급자 이름을 인코딩하고 슬롯을 붙인다', () => {
    expect(socialStartUrl('github', 1)).toBe('/api/v1/auth/social/github/start?slot=1');
    expect(socialStartUrl('a/b', 0)).toBe('/api/v1/auth/social/a%2Fb/start?slot=0');
  });
});

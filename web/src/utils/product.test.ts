import { describe, expect, it } from 'vitest';
import { inProduct, productOf, productOfRedirectUri } from './product';

describe('productOf', () => {
  it('/dev 와 그 아래 경로만 개발자 제품이다', () => {
    for (const p of ['/dev', '/dev/', '/dev/logs', '/dev/account']) expect(productOf(p)).toBe('dev');
  });

  it('이름이 비슷한 다른 경로와 일반 경로는 허브다', () => {
    for (const p of ['/', '/portal', '/login', '/account', '/logs', '/device', '/developers', '/dev-tools']) {
      expect(productOf(p)).toBe('hub');
    }
  });
});

describe('inProduct', () => {
  it('허브는 경로를 그대로, 개발자 제품은 /dev 를 앞에 붙인다', () => {
    expect(inProduct('hub', '/logs')).toBe('/logs');
    expect(inProduct('hub', '/')).toBe('/');
    expect(inProduct('dev', '/logs')).toBe('/dev/logs');
    expect(inProduct('dev', '/account')).toBe('/dev/account');
    expect(inProduct('dev', '/')).toBe('/dev/');
  });
});

describe('productOfRedirectUri', () => {
  it('/dev/ 아래로 돌아오는 서비스는 개발자 제품이다', () => {
    expect(productOfRedirectUri('https://doro.example.com/dev/blog/api/v1/bff/callback')).toBe('dev');
    expect(productOfRedirectUri('https://doro.example.com/dev/interview/api/v1/bff/callback')).toBe('dev');
  });

  it('일반 서비스·잘못된 주소·없는 값은 허브다', () => {
    expect(productOfRedirectUri('https://doro.example.com/party/api/v1/bff/callback')).toBe('hub');
    expect(productOfRedirectUri('https://doro.example.com/developers/x')).toBe('hub');
    expect(productOfRedirectUri('not a url')).toBe('hub');
    expect(productOfRedirectUri(null)).toBe('hub');
    expect(productOfRedirectUri(undefined)).toBe('hub');
  });
});

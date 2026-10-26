import { describe, expect, it } from 'vitest';
import { inProduct, productOf } from './product';

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

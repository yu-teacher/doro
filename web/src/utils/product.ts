/**
 * 같은 포털 앱이 두 제품의 입구를 겸한다: 일반 제품(Doro 허브, /)과 개발자 제품(Doro Dev, /dev/).
 * 로그인(Doro 계정)만 공유하고, 화면은 들어온 입구의 서비스만 보여 준다. 어느 입구인지는 주소(pathname)로만 정한다.
 */
export type Product = 'hub' | 'dev';

export const DEV_ROOT = '/dev';

/** /dev, /dev/, /dev/logs 같은 주소는 개발자 제품이다(/device 같은 다른 이름은 아니다). */
export function productOf(pathname: string): Product {
  return pathname === DEV_ROOT || pathname.startsWith(`${DEV_ROOT}/`) ? 'dev' : 'hub';
}

/** 입구(제품)에 맞춰 포털 안의 경로를 만든다. 예: ('dev', '/logs') → '/dev/logs', ('hub', '/logs') → '/logs' */
export function inProduct(product: Product, path: string): string {
  if (product === 'hub') return path;
  return path === '/' ? `${DEV_ROOT}/` : `${DEV_ROOT}${path}`;
}

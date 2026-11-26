import { productOf, type Product } from './product';

/**
 * 제품별 설치 앱 정의(manifest)와 iOS 홈 화면 이름. 같은 index.html 이 두 입구를 겸하므로 주소에 맞춰 바꾼다.
 * Doro Dev 입구(/dev)에서 "홈 화면에 추가"하면 일반 Doro(start_url /)가 아니라 Doro Dev(start_url /dev)가 설치되어야 한다.
 * Doro Dev 앱 정의는 public/manifest-dev.webmanifest 이고, 게이트웨이의 루트 PWA 파일 목록에 있어야 서빙된다.
 * 범위(scope)는 둘 다 / 다: 로그인·동의 화면(/login, /oauth2/…)이 루트에 있어 앱 안에서 이어져야 한다.
 */
const PRODUCT_MANIFEST: Record<Product, { href: string; title: string }> = {
  hub: { href: '/manifest.webmanifest', title: 'Doro' },
  dev: { href: '/manifest-dev.webmanifest', title: 'Doro Dev' },
};

export function applyProductManifest(pathname: string, doc: Document = document): void {
  const { href, title } = PRODUCT_MANIFEST[productOf(pathname)];
  let link = doc.querySelector<HTMLLinkElement>('link[rel="manifest"]');
  if (!link) {
    link = doc.createElement('link');
    link.rel = 'manifest';
    doc.head.appendChild(link);
  }
  if (link.getAttribute('href') !== href) {
    link.setAttribute('href', href);
  }
  const appTitle = doc.querySelector<HTMLMetaElement>('meta[name="apple-mobile-web-app-title"]');
  if (appTitle && appTitle.content !== title) {
    appTitle.content = title;
  }
}

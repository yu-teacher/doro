import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { applyProductManifest } from './productManifest';

function setupHead() {
  document.head.innerHTML =
    '<link rel="manifest" href="/manifest.webmanifest">' +
    '<meta name="apple-mobile-web-app-title" content="Doro">';
}

const manifestHref = () => document.querySelector('link[rel="manifest"]')?.getAttribute('href');
const appTitle = () => document.querySelector<HTMLMetaElement>('meta[name="apple-mobile-web-app-title"]')?.content;

describe('applyProductManifest', () => {
  afterEach(() => {
    document.head.innerHTML = '';
  });

  it('Doro Dev 입구(/dev, /dev/account, /dev/logs)에서는 Doro Dev 앱 정의와 이름을 쓴다', () => {
    for (const path of ['/dev', '/dev/', '/dev/account', '/dev/logs']) {
      setupHead();
      applyProductManifest(path);
      expect(manifestHref()).toBe('/manifest-dev.webmanifest');
      expect(appTitle()).toBe('Doro Dev');
    }
  });

  it('일반 입구로 돌아오면 일반 Doro 앱 정의로 되돌린다(/device 같은 이름은 일반이다)', () => {
    setupHead();
    applyProductManifest('/dev');
    for (const path of ['/', '/account', '/logs', '/device']) {
      applyProductManifest(path);
      expect(manifestHref()).toBe('/manifest.webmanifest');
      expect(appTitle()).toBe('Doro');
    }
  });

  it('앱 정의 링크가 없으면 만들어 붙인다', () => {
    applyProductManifest('/dev');
    expect(manifestHref()).toBe('/manifest-dev.webmanifest');
  });
});

describe('public/manifest-dev.webmanifest', () => {
  // vitest 는 web 폴더에서 돈다(jsdom 에서는 import.meta.url 이 파일 주소가 아니다)
  const manifest = JSON.parse(readFileSync(resolve(process.cwd(), 'public/manifest-dev.webmanifest'), 'utf8'));

  it('설치하면 Doro Dev 허브(/dev)로 시작하고, 일반 Doro 와 다른 앱으로 구분된다', () => {
    expect(manifest.name).toBe('Doro Dev');
    expect(manifest.start_url).toBe('/dev');
    expect(manifest.id).toBe('/dev'); // 일반 Doro 의 id 는 start_url(/) 이다
    expect(manifest.scope).toBe('/');
  });

  it('아이콘은 허브 PWA 파일과 같은 루트 파일을 쓴다', () => {
    expect(manifest.icons.map((icon: { src: string }) => icon.src)).toEqual(['/icon-192.png', '/icon-512.png']);
  });
});

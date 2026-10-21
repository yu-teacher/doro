import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { buildServicesManifest, PUBLIC_SERVICES } from './servicesManifest';
import { buildLauncherApps, buildServiceCards } from './serviceCatalog';

beforeEach(() => {
  // 게이트웨이(80/443)로 접속한 것처럼: 서비스 카드·앱 목록이 하위 경로를 가리킨다
  vi.stubGlobal('window', { location: { port: '', protocol: 'https:', hostname: 'doro.example' } });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('services.json 원본(PUBLIC_SERVICES)', () => {
  it('허브 서비스 카드와 같은 서비스·이름·아이콘·경로다 (새 서비스를 한쪽에만 더하면 실패한다)', () => {
    const cards = buildServiceCards({ isLoggedIn: false, isAdmin: false }).filter((card) => card.group === 'service');
    expect(PUBLIC_SERVICES.map((s) => [s.id, s.name, s.icon, s.href])).toEqual(
      cards.map((c) => [c.id, c.name, c.icon, c.href]),
    );
  });

  it('헤더 앱 목록(허브)의 서비스 항목과도 같은 서비스·이름·경로다', () => {
    const apps = buildLauncherApps({ isAdmin: false }).filter((app) => app.id !== 'hub');
    expect(PUBLIC_SERVICES.map((s) => [s.id, s.name, s.href])).toEqual(apps.map((a) => [a.id, a.name, a.href]));
  });

  it('경로는 게이트웨이 기준 /로 시작해 /로 끝나고 서로 겹치지 않으며 id 도 유일하다', () => {
    for (const service of PUBLIC_SERVICES) {
      expect(service.href).toMatch(/^\/[a-z0-9-]+\/$/);
    }
    expect(new Set(PUBLIC_SERVICES.map((s) => s.href)).size).toBe(PUBLIC_SERVICES.length);
    expect(new Set(PUBLIC_SERVICES.map((s) => s.id)).size).toBe(PUBLIC_SERVICES.length);
  });

  it('JSON 형식은 version 1 과 services 배열이며 직렬화해도 같다', () => {
    const manifest = buildServicesManifest();
    expect(manifest.version).toBe(1);
    expect(JSON.parse(JSON.stringify(manifest))).toEqual(manifest);
  });
});

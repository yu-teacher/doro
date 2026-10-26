import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { buildLauncherApps, buildServiceCards } from './serviceCatalog';

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
});

/** 테스트가 jsdom 기본 주소(localhost:3000)에 기대지 않도록 접속 위치를 명시한다. port '' 는 80/443(게이트웨이)이다. */
function visitFrom(port: string) {
  vi.stubGlobal('window', { location: { port, protocol: 'http:', hostname: 'mini.local' } });
}

beforeEach(() => {
  visitFrom('');
});

const ids = (cards: { id: string }[]) => cards.map((c) => c.id);

describe('buildServiceCards', () => {
  it('비로그인 일반 사용자: 서비스 5개와 로그인 안내 카드만 보인다', () => {
    const cards = buildServiceCards({ isLoggedIn: false, isAdmin: false });
    expect(ids(cards)).toEqual(['blog', 'party', 'games', 'interview', 'menu', 'account']);
    const account = cards.find((c) => c.id === 'account')!;
    expect(account.href).toBe('/login');
    expect(account.internal).toBe(true);
  });

  it('개발 블로그·개발 면접은 개발자 라운지(developer), 나머지 서비스는 일반(general)이다', () => {
    const cards = buildServiceCards({ isLoggedIn: false, isAdmin: false }).filter((c) => c.group === 'service');
    const by = (a: string) => cards.filter((c) => c.audience === a).map((c) => c.id);
    expect(by('developer')).toEqual(['blog', 'interview']);
    expect(by('general')).toEqual(['party', 'games', 'menu']);
  });

  it('로그인하면 계정 카드가 내 계정 화면으로 간다', () => {
    const account = buildServiceCards({ isLoggedIn: true, isAdmin: false }).find((c) => c.id === 'account')!;
    expect(account.href).toBe('/account');
  });

  it('게이트웨이(80/443)로 접속하면 서비스 카드는 하위 경로를 가리킨다(파티·게임·메뉴)', () => {
    visitFrom('');
    const byId = Object.fromEntries(buildServiceCards({ isLoggedIn: false, isAdmin: false }).map((c) => [c.id, c.href]));
    expect(byId.party).toBe('/party/');
    expect(byId.games).toBe('/games/');
    expect(byId.interview).toBe('/interview/');
    expect(byId.menu).toBe('/menu/');
  });

  it('3000 포트로 직접 접속하면(개발/호스트 직접 접근) 각 서비스의 포트로 보낸다', () => {
    visitFrom('3000');
    const byId = Object.fromEntries(buildServiceCards({ isLoggedIn: false, isAdmin: false }).map((c) => [c.id, c.href]));
    expect(byId.party).toBe('http://mini.local:3005/party/');
    expect(byId.games).toBe('http://mini.local:3006/games/');
    expect(byId.interview).toBe('http://mini.local:3007/interview/');
    expect(byId.menu).toBe('http://mini.local:3003/');
  });

  it('서비스는 다른 앱이라 전체 페이지 이동(internal=false)이다', () => {
    const cards = buildServiceCards({ isLoggedIn: true, isAdmin: false }).filter((c) => c.group === 'service');
    expect(cards).toHaveLength(5);
    expect(cards.every((c) => !c.internal)).toBe(true);
  });

  it('일반 사용자에게는 관리자용 카드(문서, 로그)를 보이지 않는다', () => {
    vi.stubEnv('VITE_GUARD_DOCS_URL', 'https://guard.example.com/docs');
    vi.stubEnv('VITE_IAM_DOCS_URL', 'https://iam.example.com/docs');
    const cards = buildServiceCards({ isLoggedIn: true, isAdmin: false });
    expect(ids(cards)).not.toContain('logs');
    expect(ids(cards)).not.toContain('guard-docs');
    expect(ids(cards)).not.toContain('iam-docs');
  });

  it('관리자: 로그 카드는 항상, 문서 카드는 환경변수가 있을 때만 보인다', () => {
    vi.stubEnv('VITE_GUARD_DOCS_URL', '');
    vi.stubEnv('VITE_IAM_DOCS_URL', '');
    expect(ids(buildServiceCards({ isLoggedIn: true, isAdmin: true }))).toEqual(['blog', 'party', 'games', 'interview', 'menu', 'account', 'logs']);

    vi.stubEnv('VITE_GUARD_DOCS_URL', 'https://guard.example.com/docs');
    vi.stubEnv('VITE_IAM_DOCS_URL', 'https://iam.example.com/docs');
    const adminCards = buildServiceCards({ isLoggedIn: true, isAdmin: true });
    expect(ids(adminCards)).toEqual(['blog', 'party', 'games', 'interview', 'menu', 'account', 'iam-docs', 'guard-docs', 'logs']);
    expect(adminCards.find((c) => c.id === 'guard-docs')!.href).toBe('https://guard.example.com/docs');
  });

  it('환경변수로 서비스 주소를 덮어쓸 수 있다(도메인 이전 대비)', () => {
    vi.stubEnv('VITE_PARTY_URL', 'https://party.example.com/');
    vi.stubEnv('VITE_GAMES_URL', 'https://games.example.com/');
    const byId = Object.fromEntries(buildServiceCards({ isLoggedIn: false, isAdmin: false }).map((c) => [c.id, c.href]));
    expect(byId.party).toBe('https://party.example.com/');
    expect(byId.games).toBe('https://games.example.com/');
  });
});

describe('개발자 제품(Doro Dev) 입구', () => {
  it('개발자 입구에는 개발자 서비스만 나오고 일반 서비스는 하나도 없다', () => {
    const cards = buildServiceCards({ isLoggedIn: true, isAdmin: false, product: 'dev' });
    expect(ids(cards)).toEqual(['blog', 'interview']);
  });

  it('관리자: 개발자 입구에 문서와 로그가 더해지고 로그는 /dev/logs 로 간다', () => {
    vi.stubEnv('VITE_GUARD_DOCS_URL', 'https://guard.example.com/docs');
    vi.stubEnv('VITE_IAM_DOCS_URL', 'https://iam.example.com/docs');
    const cards = buildServiceCards({ isLoggedIn: true, isAdmin: true, product: 'dev' });
    expect(ids(cards)).toEqual(['blog', 'interview', 'iam-docs', 'guard-docs', 'logs']);
    expect(cards.find((c) => c.id === 'logs')!.href).toBe('/dev/logs');
  });

  it('생략하면 허브다: 로그 카드는 /logs 로 가고 지금은 개발자 서비스도 함께 보인다(숨김 설정 전)', () => {
    const cards = buildServiceCards({ isLoggedIn: true, isAdmin: true });
    expect(cards.find((c) => c.id === 'logs')!.href).toBe('/logs');
    expect(ids(cards)).toContain('blog');
  });

  it('개발자 입구의 앱 목록: 맨 앞은 Doro Dev 홈(/dev/)이고 일반 서비스는 없다', () => {
    const apps = buildLauncherApps({ isAdmin: false, product: 'dev' });
    expect(ids(apps)).toEqual(['hub', 'blog', 'interview']);
    expect(apps[0].href).toBe('/dev/');
    expect(apps[0].internal).toBe(true);
  });

  it('관리자 앱 목록의 로그는 /dev/logs 이고 일반 서비스는 여전히 없다', () => {
    vi.stubEnv('VITE_GUARD_DOCS_URL', 'https://guard.example.com/docs');
    const apps = buildLauncherApps({ isAdmin: true, product: 'dev' });
    expect(ids(apps)).toEqual(['hub', 'blog', 'interview', 'guard-docs', 'logs']);
    expect(apps.find((a) => a.id === 'logs')!.href).toBe('/dev/logs');
  });
});

describe('buildLauncherApps (헤더 앱 목록)', () => {
  it('맨 앞이 메인(허브)이라 어느 화면에서든 돌아갈 수 있고, 일반 사용자는 서비스 5개가 이어진다', () => {
    const apps = buildLauncherApps({ isAdmin: false });
    expect(ids(apps)).toEqual(['hub', 'blog', 'party', 'games', 'interview', 'menu']);
    const hub = apps[0];
    expect(hub.href).toBe('/');
    expect(hub.internal).toBe(true);
  });

  it('서비스 주소는 허브 카드와 같다(한곳에서만 가져온다)', () => {
    const fromHub = Object.fromEntries(buildServiceCards({ isLoggedIn: false, isAdmin: false }).map((c) => [c.id, c.href]));
    const fromLauncher = Object.fromEntries(buildLauncherApps({ isAdmin: false }).map((a) => [a.id, a.href]));
    for (const id of ['blog', 'party', 'games', 'interview', 'menu']) expect(fromLauncher[id]).toBe(fromHub[id]);
  });

  it('관리자에게만 로그(내부)와 문서(주입된 경우)가 더해진다', () => {
    vi.stubEnv('VITE_GUARD_DOCS_URL', 'https://guard.example.com/docs');
    expect(ids(buildLauncherApps({ isAdmin: false }))).not.toContain('logs');
    const admin = buildLauncherApps({ isAdmin: true });
    expect(ids(admin)).toEqual(['hub', 'blog', 'party', 'games', 'interview', 'menu', 'guard-docs', 'logs']);
    expect(admin.find((a) => a.id === 'logs')!.internal).toBe(true);
    expect(admin.find((a) => a.id === 'guard-docs')!.internal).toBe(false);
  });

  it('문서 URL 이 없으면 Guard 문서 항목은 숨긴다', () => {
    vi.stubEnv('VITE_GUARD_DOCS_URL', '');
    expect(ids(buildLauncherApps({ isAdmin: true }))).toEqual(['hub', 'blog', 'party', 'games', 'interview', 'menu', 'logs']);
  });
});

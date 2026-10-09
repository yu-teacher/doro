import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { buildServiceCards } from './serviceCatalog';

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
  it('비로그인 일반 사용자: 서비스 4개와 로그인 안내 카드만 보인다', () => {
    const cards = buildServiceCards({ isLoggedIn: false, isAdmin: false });
    expect(ids(cards)).toEqual(['blog', 'party', 'games', 'menu', 'account']);
    const account = cards.find((c) => c.id === 'account')!;
    expect(account.href).toBe('/login');
    expect(account.internal).toBe(true);
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
    expect(byId.menu).toBe('/menu/');
  });

  it('3000 포트로 직접 접속하면(개발/호스트 직접 접근) 각 서비스의 포트로 보낸다', () => {
    visitFrom('3000');
    const byId = Object.fromEntries(buildServiceCards({ isLoggedIn: false, isAdmin: false }).map((c) => [c.id, c.href]));
    expect(byId.party).toBe('http://mini.local:3005/party/');
    expect(byId.games).toBe('http://mini.local:3006/games/');
    expect(byId.menu).toBe('http://mini.local:3003/');
  });

  it('서비스는 다른 앱이라 전체 페이지 이동(internal=false)이다', () => {
    const cards = buildServiceCards({ isLoggedIn: true, isAdmin: false }).filter((c) => c.group === 'service');
    expect(cards).toHaveLength(4);
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
    expect(ids(buildServiceCards({ isLoggedIn: true, isAdmin: true }))).toEqual(['blog', 'party', 'games', 'menu', 'account', 'logs']);

    vi.stubEnv('VITE_GUARD_DOCS_URL', 'https://guard.example.com/docs');
    vi.stubEnv('VITE_IAM_DOCS_URL', 'https://iam.example.com/docs');
    const adminCards = buildServiceCards({ isLoggedIn: true, isAdmin: true });
    expect(ids(adminCards)).toEqual(['blog', 'party', 'games', 'menu', 'account', 'iam-docs', 'guard-docs', 'logs']);
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

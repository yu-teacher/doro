import { inProduct, type Product } from './product';
import { getBlogUrl, getGamesUrl, getGuardDocsUrl, getIamDocsUrl, getInterviewUrl, getMenuUrl, getPartyUrl } from './urlUtils';

/** 허브 카드가 쓰는 아이콘 이름. 화면(HubPage)이 이름을 실제 아이콘으로 바꾼다(이 파일은 화면 라이브러리에 의존하지 않는다). */
export type ServiceIcon = 'blog' | 'party' | 'games' | 'interview' | 'menu' | 'account' | 'guard' | 'iam' | 'logs';

export type ServiceGroup = 'service' | 'platform';

/** 서비스의 대상. 'developer' 서비스는 허브에서 "개발자 라운지"로 따로 묶인다(개발 블로그, 개발 면접). */
export type ServiceAudience = 'general' | 'developer';

export interface ServiceCard {
  id: string;
  name: string;
  desc: string;
  icon: ServiceIcon;
  /** 이동할 주소. 서비스는 게이트웨이 하위 경로(/party/ 등), 계정은 포털 안의 경로다. */
  href: string;
  group: ServiceGroup;
  audience: ServiceAudience;
  /** true 면 같은 포털 안의 화면이라 라우터로 이동하고, false 면 다른 서비스라 전체 페이지 이동이다. */
  internal: boolean;
}

export interface CatalogOptions {
  isLoggedIn: boolean;
  isAdmin: boolean;
  /** 어느 입구의 화면인지. 생략하면 일반 제품(허브)이다. */
  product?: Product;
}

/**
 * 일반 제품(허브)에서 개발자 서비스(블로그·면접·문서·로그)를 숨길지. 두 제품은 로그인(Doro 계정)만 공유하므로 true 다.
 * 개발자 서비스는 개발자 입구(/dev/)에서만 보인다.
 */
export const HIDE_DEV_ON_HUB = true;

/**
 * 입구(제품)에 보여 줄 대상인지: 개발자 입구는 개발자 항목만, 허브는 (숨김 설정이 켜지면) 개발자 항목을 뺀 나머지.
 * 관리자(운영자)는 양쪽을 오가야 하므로 어느 입구에서든 두 제품의 서비스를 모두 본다. 일반 사용자에게는 상대 쪽이 보이지 않는다.
 */
function visibleIn(product: Product, audience: ServiceAudience, isAdmin: boolean): boolean {
  if (isAdmin) return true;
  if (product === 'dev') return audience === 'developer';
  return !HIDE_DEV_ON_HUB || audience === 'general';
}

/**
 * 허브에 보여줄 카드 목록. 서비스 주소를 한곳(urlUtils)에서만 가져오므로 주소가 바뀌어도 여기는 그대로다.
 * - 서비스(group=service): 블로그, 도로 파티, 도로 게임 천국, 도로 면접, 도로메뉴
 * - 플랫폼(group=platform): Doro 계정(로그인 여부에 따라 안내가 달라진다), 관리자에게만 개발자 문서와 관제 로그
 * 문서 URL 은 환경변수로만 주입되며 비어 있으면 카드를 숨긴다(기존 앱 목록과 같은 규칙).
 */
export function buildServiceCards({ isLoggedIn, isAdmin, product = 'hub' }: CatalogOptions): ServiceCard[] {
  const cards: ServiceCard[] = [
    { id: 'blog', name: 'DORO.log', desc: '개발자 오픈 기술 블로그', icon: 'blog', href: getBlogUrl(), group: 'service', audience: 'developer', internal: false },
    { id: 'party', name: '도로 파티', desc: '친구들과 지도에 핀을 모아 "오늘 어디 가지?"를 정해요', icon: 'party', href: getPartyUrl(), group: 'service', audience: 'general', internal: false },
    { id: 'games', name: '도로 게임 천국', desc: '잠깐 쉬어 가는 웹 미니게임', icon: 'games', href: getGamesUrl(), group: 'service', audience: 'general', internal: false },
    { id: 'interview', name: '도로 면접', desc: '개발자 면접 질문을 읽고, 체크하고, 랜덤으로 풀어 봐요', icon: 'interview', href: getInterviewUrl(), group: 'service', audience: 'developer', internal: false },
    { id: 'menu', name: '도로메뉴', desc: '메뉴 정해주는 도로롱', icon: 'menu', href: getMenuUrl(), group: 'service', audience: 'general', internal: false },
    {
      id: 'account',
      name: 'Doro 계정',
      desc: isLoggedIn ? '프로필, 보안(2단계 인증), 로그인한 기기 관리' : '한 번 로그인으로 모든 서비스를 쓰고, 계정과 보안을 관리해요',
      icon: 'account',
      href: isLoggedIn ? inProduct(product, '/account') : '/login',
      group: 'platform',
      audience: 'general',
      internal: true,
    },
  ];

  if (isAdmin) {
    const guardDocs = getGuardDocsUrl();
    const iamDocs = getIamDocsUrl();
    if (iamDocs) cards.push({ id: 'iam-docs', name: 'Doro IAM', desc: '인증·OAuth API 문서', icon: 'iam', href: iamDocs, group: 'platform', audience: 'developer', internal: false });
    if (guardDocs) cards.push({ id: 'guard-docs', name: 'Doro Guard', desc: '권한 판정(Zanzibar ReBAC) API 문서', icon: 'guard', href: guardDocs, group: 'platform', audience: 'developer', internal: false });
    cards.push({ id: 'logs', name: 'Doro Ops Logs', desc: '시스템 관제 로그', icon: 'logs', href: inProduct(product, '/logs'), group: 'platform', audience: 'developer', internal: true });
  }
  return cards.filter((card) => visibleIn(product, card.audience, isAdmin));
}

/** 앱 목록(헤더의 9개 점)이 쓰는 아이콘 이름. 허브로 돌아가는 "home" 이 더해진다. */
export type LauncherIcon = ServiceIcon | 'home';

export interface LauncherApp {
  id: string;
  name: string;
  desc: string;
  icon: LauncherIcon;
  audience: ServiceAudience;
  href: string;
  /** true 면 같은 포털 안의 화면(라우터 이동), false 면 다른 서비스나 외부 문서(전체 페이지 이동) */
  internal: boolean;
}

/**
 * 헤더 앱 목록에 보여줄 항목. 맨 앞은 들어온 입구의 메인(허브 또는 Doro Dev)이라 어느 화면에서든 돌아갈 수 있고,
 * 서비스 주소는 허브와 같은 곳(urlUtils)에서 가져온다. 문서 URL 은 환경변수로만 주입되며 비어 있으면 숨긴다.
 */
export function buildLauncherApps({ isAdmin, product = 'hub' }: { isAdmin: boolean; product?: Product }): LauncherApp[] {
  const home: LauncherApp =
    product === 'dev'
      ? { id: 'hub', name: 'Doro Dev 홈', desc: '개발자 서비스 한눈에', icon: 'home', audience: 'developer', href: inProduct('dev', '/'), internal: true }
      : { id: 'hub', name: 'Doro 홈', desc: '모든 서비스 한눈에', icon: 'home', audience: 'general', href: '/', internal: true };
  // 관리자는 다른 제품의 홈으로도 바로 갈 수 있다(일반 사용자에게는 보이지 않는다).
  const otherHome: LauncherApp[] = isAdmin
    ? [
        product === 'dev'
          ? { id: 'other-home', name: 'Doro 홈', desc: '일반 서비스 허브', icon: 'home', audience: 'general', href: '/', internal: true }
          : { id: 'other-home', name: 'Doro Dev 홈', desc: '개발자 서비스', icon: 'home', audience: 'developer', href: inProduct('dev', '/'), internal: true },
      ]
    : [];
  const apps: LauncherApp[] = [
    home,
    ...otherHome,
    { id: 'blog', name: 'DORO.log', desc: '개발자 오픈 기술 블로그', icon: 'blog', audience: 'developer', href: getBlogUrl(), internal: false },
    { id: 'party', name: '도로 파티', desc: '친구들과 지도에 핀 모으기', icon: 'party', audience: 'general', href: getPartyUrl(), internal: false },
    { id: 'games', name: '도로 게임 천국', desc: '웹 미니게임', icon: 'games', audience: 'general', href: getGamesUrl(), internal: false },
    { id: 'interview', name: '도로 면접', desc: '면접 질문 은행', icon: 'interview', audience: 'developer', href: getInterviewUrl(), internal: false },
    { id: 'menu', name: '도로메뉴', desc: '메뉴 정해주는 도로롱', icon: 'menu', audience: 'general', href: getMenuUrl(), internal: false },
  ];
  if (isAdmin) {
    const guardDocs = getGuardDocsUrl();
    if (guardDocs) apps.push({ id: 'guard-docs', name: 'Doro Guard', desc: 'Zanzibar ReBAC API', icon: 'guard', audience: 'developer', href: guardDocs, internal: false });
    apps.push({ id: 'logs', name: 'Doro Ops Logs', desc: '시스템 관제 로그', icon: 'logs', audience: 'developer', href: inProduct(product, '/logs'), internal: true });
  }
  return apps.filter((app) => app.id === 'hub' || app.id === 'other-home' || visibleIn(product, app.audience, isAdmin));
}

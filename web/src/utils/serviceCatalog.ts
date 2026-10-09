import { getBlogUrl, getGamesUrl, getGuardDocsUrl, getIamDocsUrl, getMenuUrl, getPartyUrl } from './urlUtils';

/** 허브 카드가 쓰는 아이콘 이름. 화면(HubPage)이 이름을 실제 아이콘으로 바꾼다(이 파일은 화면 라이브러리에 의존하지 않는다). */
export type ServiceIcon = 'blog' | 'party' | 'games' | 'menu' | 'account' | 'guard' | 'iam' | 'logs';

export type ServiceGroup = 'service' | 'platform';

export interface ServiceCard {
  id: string;
  name: string;
  desc: string;
  icon: ServiceIcon;
  /** 이동할 주소. 서비스는 게이트웨이 하위 경로(/party/ 등), 계정은 포털 안의 경로다. */
  href: string;
  group: ServiceGroup;
  /** true 면 같은 포털 안의 화면이라 라우터로 이동하고, false 면 다른 서비스라 전체 페이지 이동이다. */
  internal: boolean;
}

export interface CatalogOptions {
  isLoggedIn: boolean;
  isAdmin: boolean;
}

/**
 * 허브에 보여줄 카드 목록. 서비스 주소를 한곳(urlUtils)에서만 가져오므로 주소가 바뀌어도 여기는 그대로다.
 * - 서비스(group=service): 블로그, 도로 파티, 도로 게임 천국, 도로메뉴
 * - 플랫폼(group=platform): Doro 계정(로그인 여부에 따라 안내가 달라진다), 관리자에게만 개발자 문서와 관제 로그
 * 문서 URL 은 환경변수로만 주입되며 비어 있으면 카드를 숨긴다(기존 앱 목록과 같은 규칙).
 */
export function buildServiceCards({ isLoggedIn, isAdmin }: CatalogOptions): ServiceCard[] {
  const cards: ServiceCard[] = [
    { id: 'blog', name: 'DORO.log', desc: '개발자 오픈 기술 블로그', icon: 'blog', href: getBlogUrl(), group: 'service', internal: false },
    { id: 'party', name: '도로 파티', desc: '친구들과 지도에 핀을 모아 "오늘 어디 가지?"를 정해요', icon: 'party', href: getPartyUrl(), group: 'service', internal: false },
    { id: 'games', name: '도로 게임 천국', desc: '잠깐 쉬어 가는 웹 미니게임', icon: 'games', href: getGamesUrl(), group: 'service', internal: false },
    { id: 'menu', name: '도로메뉴', desc: '메뉴 정해주는 도로롱', icon: 'menu', href: getMenuUrl(), group: 'service', internal: false },
    {
      id: 'account',
      name: 'Doro 계정',
      desc: isLoggedIn ? '프로필, 보안(2단계 인증), 로그인한 기기 관리' : '한 번 로그인으로 모든 서비스를 쓰고, 계정과 보안을 관리해요',
      icon: 'account',
      href: isLoggedIn ? '/account' : '/login',
      group: 'platform',
      internal: true,
    },
  ];

  if (isAdmin) {
    const guardDocs = getGuardDocsUrl();
    const iamDocs = getIamDocsUrl();
    if (iamDocs) cards.push({ id: 'iam-docs', name: 'Doro IAM', desc: '인증·OAuth API 문서', icon: 'iam', href: iamDocs, group: 'platform', internal: false });
    if (guardDocs) cards.push({ id: 'guard-docs', name: 'Doro Guard', desc: '권한 판정(Zanzibar ReBAC) API 문서', icon: 'guard', href: guardDocs, group: 'platform', internal: false });
    cards.push({ id: 'logs', name: 'Doro Ops Logs', desc: '시스템 관제 로그', icon: 'logs', href: '/logs', group: 'platform', internal: true });
  }
  return cards;
}

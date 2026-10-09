/**
 * Doro 서브 서비스 및 플랫폼 URL 해석 유틸리티
 * 로컬 개발 환경(포트 3000 / 3002), mini 서버 직접 접속, 리버스 프록시 게이트웨이(포트 80)를 모두 자동 지원합니다.
 */
export const getBlogUrl = (): string => {
  if (import.meta.env.VITE_BLOG_URL) {
    return import.meta.env.VITE_BLOG_URL;
  }
  if (typeof window !== 'undefined') {
    // 포트 3000으로 직접 접속한 경우 (로컬 개발 또는 호스트 직접 접근): 블로그는 3002 포트
    if (window.location.port === '3000') {
      return `${window.location.protocol}//${window.location.hostname}:3002/`;
    }
    // 게이트웨이: DORO.log 블로그는 '/blog/' (루트 '/'는 허브)
    return '/blog/';
  }
  return '/blog/';
};

export const getMenuUrl = (): string => {
  if (import.meta.env.VITE_MENU_URL) {
    return import.meta.env.VITE_MENU_URL;
  }
  if (typeof window !== 'undefined') {
    // 포트 3000으로 직접 접속한 경우 (로컬 개발 또는 호스트 직접 접근): 도로메뉴는 3003 포트
    if (window.location.port === '3000') {
      return `${window.location.protocol}//${window.location.hostname}:3003/`;
    }
    // 게이트웨이(포트 80): '/menu/'
    return '/menu/';
  }
  return '/menu/';
};


/** 도로 파티 URL(VITE_PARTY_URL 로 덮어쓴다). 게이트웨이 기준 '/party/', 3000 포트 직접 접속이면 3005 포트 */
export const getPartyUrl = (): string => {
  if (import.meta.env.VITE_PARTY_URL) {
    return import.meta.env.VITE_PARTY_URL;
  }
  if (typeof window !== 'undefined' && window.location.port === '3000') {
    return `${window.location.protocol}//${window.location.hostname}:3005/party/`;
  }
  return '/party/';
};

/** 도로 게임 천국 URL(VITE_GAMES_URL 로 덮어쓴다). 게이트웨이 기준 '/games/', 3000 포트 직접 접속이면 3006 포트 */
export const getGamesUrl = (): string => {
  if (import.meta.env.VITE_GAMES_URL) {
    return import.meta.env.VITE_GAMES_URL;
  }
  if (typeof window !== 'undefined' && window.location.port === '3000') {
    return `${window.location.protocol}//${window.location.hostname}:3006/games/`;
  }
  return '/games/';
};

const ALLOWED_DOCS_PROTOCOLS = new Set(['http:', 'https:']);

/**
 * 선택적 외부 문서 URL 을 검증한다. 비어 있거나 http(s) 절대 URL 이 아니면 null 을 반환한다.
 * (javascript: 등 임의 스킴이 링크로 렌더링되는 것을 막는다)
 */
export const resolveOptionalHttpUrl = (value: string | undefined | null): string | null => {
  const trimmed = value?.trim();
  if (!trimmed) {
    return null;
  }
  try {
    const parsed = new URL(trimmed);
    return ALLOWED_DOCS_PROTOCOLS.has(parsed.protocol) ? trimmed : null;
  } catch {
    return null;
  }
};

/** Doro Guard OpenAPI 문서 URL (VITE_GUARD_DOCS_URL). 미설정이면 null */
export const getGuardDocsUrl = (): string | null => resolveOptionalHttpUrl(import.meta.env.VITE_GUARD_DOCS_URL);

/** 중앙 Grafana 콘솔 URL (VITE_GRAFANA_URL). 미설정이면 null 이고 로그 화면의 링크를 숨긴다 */
export const getGrafanaUrl = (): string | null => resolveOptionalHttpUrl(import.meta.env.VITE_GRAFANA_URL);

/** Doro IAM OpenAPI 문서 URL (VITE_IAM_DOCS_URL). 미설정이면 null */
export const getIamDocsUrl = (): string | null => resolveOptionalHttpUrl(import.meta.env.VITE_IAM_DOCS_URL);

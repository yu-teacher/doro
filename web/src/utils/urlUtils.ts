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
    // 게이트웨이(포트 80, e.g. http://112.156.246.132/): 루트 '/'가 DORO.log 블로그
    return '/';
  }
  return '/';
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

/** Doro IAM OpenAPI 문서 URL (VITE_IAM_DOCS_URL). 미설정이면 null */
export const getIamDocsUrl = (): string | null => resolveOptionalHttpUrl(import.meta.env.VITE_IAM_DOCS_URL);

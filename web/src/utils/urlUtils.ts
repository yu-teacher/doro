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


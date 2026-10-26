import { useMemo } from 'react';
import { useLocation } from 'react-router-dom';
import { peekConsentRedirectUri } from './consentReturn';
import { readReturnPath } from './afterLogin';
import { productOf, productOfRedirectUri, type Product } from './product';

const AUTH_PATHS = new Set(['/login', '/signup']);

/**
 * 지금 화면이 어느 제품의 입구인지. /dev 아래는 개발자 제품이고, 로그인·가입·동의 화면은 공유 화면이라 주소만으로는 모른다.
 * 그래서 서비스(블로그·면접)가 시작한 로그인이면 그 서비스의 복귀 주소(redirect_uri)로, 개발자 입구에서 온 로그인이면 돌아갈 경로로 정한다.
 * 이렇게 해야 개발자 쪽 사용자가 로그인 화면에서 일반 허브(홈 버튼·앱 목록)를 보지 않는다.
 */
export function useProduct(): Product {
  const location = useLocation();
  return useMemo(() => {
    if (productOf(location.pathname) === 'dev') return 'dev';
    if (location.pathname === '/oauth2/consent') {
      return productOfRedirectUri(new URLSearchParams(location.search).get('redirect_uri'));
    }
    if (AUTH_PATHS.has(location.pathname)) {
      const from = readReturnPath(location.state);
      if (from !== null && productOf(from) === 'dev') return 'dev';
      return productOfRedirectUri(peekConsentRedirectUri());
    }
    return 'hub';
  }, [location.pathname, location.search, location.state]);
}

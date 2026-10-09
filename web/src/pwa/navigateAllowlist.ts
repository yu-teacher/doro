/**
 * 허브(포털) 서비스 워커가 "앱 셸(index.html)로 대신 응답해도 되는" 이동 주소.
 *
 * 허브는 게이트웨이의 루트(/)에 있고 서비스 워커 범위도 / 라서, 블로그(/blog/)·파티·게임·메뉴·OAuth·API 같은 다른 서비스로의
 * 이동까지 허브 화면으로 바꿔 버리면 안 된다. 그래서 거부 목록이 아니라 허용 목록으로 허브 자신의 라우트만 적는다.
 * (옛 블로그 주소 /@사용자 같은 것은 허용되지 않아 네트워크로 가고, 게이트웨이가 /blog 로 이동시킨다.)
 * 허브 라우트(App.tsx)가 바뀌면 여기도 함께 바꾼다.
 */
export const HUB_ROUTE_NAMES = ['portal', 'login', 'signup', 'account', 'logs'] as const;

/** 경로(pathname)+쿼리 문자열 전체에 맞춘다. 예: "/", "/login", "/login?returnTo=x", "/account/" */
export const HUB_NAVIGATE_ALLOWLIST: RegExp[] = [new RegExp(`^/(?:(?:${HUB_ROUTE_NAMES.join('|')})/?)?(?:\\?.*)?$`)];

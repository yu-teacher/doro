/** 서버(RefreshTokenCookies)와 맞춘 값. 리프레시 토큰을 HttpOnly 쿠키로 주고받게 하는 요청 헤더. */
export const COOKIE_SESSION_HEADER = 'X-Doro-Cookie-Session';
export const ACCOUNT_SLOT_HEADER = 'X-Doro-Account-Slot';

/** 한 브라우저에 동시에 둘 수 있는 계정 수. 서버의 슬롯 범위(0~4)와 같다. */
export const MAX_ACCOUNTS = 5;

export function cookieSessionHeaders(slot: number): Record<string, string> {
  return { [COOKIE_SESSION_HEADER]: '1', [ACCOUNT_SLOT_HEADER]: String(slot) };
}

/**
 * 로그인 화면 위쪽에 한 번 보여 줄 안내. 세션이 끝나 전체 페이지를 다시 불러오며 로그인으로 보낼 때(라우터 상태를 못 넘기는 경우)
 * 이유를 알려 주려고 sessionStorage 에 잠깐 둔다. 읽으면 지운다.
 */
const STORAGE_KEY = 'doro_login_notice';

/** 서버가 갱신을 거부해 로그인 상태를 정리할 때의 안내(로그인 유지 기간이 지났거나, 다른 곳에서 세션을 끝낸 경우 포함) */
export const SESSION_ENDED_NOTICE = '로그인 유지 기간이 지나 세션이 끝났습니다. 다시 로그인해 주세요.';

export function rememberLoginNotice(text: string): void {
  try {
    sessionStorage.setItem(STORAGE_KEY, text);
  } catch (error: unknown) {
    console.warn('Failed to persist the login notice', error);
  }
}

export function consumeLoginNotice(): string | null {
  try {
    const text = sessionStorage.getItem(STORAGE_KEY);
    if (text !== null) {
      sessionStorage.removeItem(STORAGE_KEY);
    }
    return text;
  } catch (error: unknown) {
    console.warn('Failed to read the login notice', error);
    return null;
  }
}

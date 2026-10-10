import { peekConsentReturnPath } from './consentReturn';

/** 어디서 왔는지 모를 때(로그인 주소를 직접 연 경우) 로그인 뒤에 가는 곳 */
export const DEFAULT_AFTER_LOGIN_PATH = '/account';

/**
 * 로그인 뒤에 돌아올 수 있는 포털 화면. 외부 주소나 임의 경로로 보내지 못하도록 이 목록에 있는 경로만 인정한다.
 * (로그인·가입 화면 자신과 동의 화면은 제외한다. 동의 화면은 consentReturn 이 따로 복원한다.)
 */
const RETURNABLE_PATHS = new Set(['/', '/portal', '/account', '/logs']);

export interface LoginNavigationState {
  /** 로그인 뒤에 돌아갈 포털 경로 */
  from?: string;
  /** 로그인 화면 위쪽에 보여 줄 안내 문구(전체 로그아웃, 탈퇴 예약 등) */
  notice?: string;
}

/** 로그인 화면으로 이동할 때 넘기는 상태: 지금 보던 화면으로 로그인 뒤에 돌아오게 한다. 돌아올 수 없는 화면이면 undefined. */
export function loginStateFrom(location: { pathname: string }): LoginNavigationState | undefined {
  return RETURNABLE_PATHS.has(location.pathname) ? { from: location.pathname } : undefined;
}

/** 라우터 상태에서 검증을 통과한 돌아갈 경로를 꺼낸다. */
export function readReturnPath(state: unknown): string | null {
  if (typeof state !== 'object' || state === null) return null;
  const from = (state as Record<string, unknown>).from;
  return typeof from === 'string' && RETURNABLE_PATHS.has(from) ? from : null;
}

/**
 * 로그인(또는 가입) 직후 이동할 곳. 우선순위:
 * 1) 서비스(블로그·파티 등)나 앱의 로그인 요청에서 넘어온 동의 요청 → 그 요청으로 복귀(승인 뒤 원래 서비스 화면으로 이어진다)
 * 2) 로그인 화면으로 오기 전에 보던 포털 화면
 * 3) 내 계정
 */
export function resolveAfterLoginPath(state: unknown): string {
  return peekConsentReturnPath() ?? readReturnPath(state) ?? DEFAULT_AFTER_LOGIN_PATH;
}

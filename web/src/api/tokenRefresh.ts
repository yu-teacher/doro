import axios, { isAxiosError } from 'axios';
import { useAuthStore } from '../store/authStore';

const REFRESH_URL = '/api/v1/auth/token/refresh';
const REFRESH_LOCK_NAME = 'doro-token-refresh';

// 서버가 리프레시 토큰 자체를 거부(만료/폐기/재사용 감지)한 것으로 보는 상태 코드
const REJECTED_STATUSES = new Set([400, 401, 403, 404]);

export type RefreshResult =
  | { kind: 'refreshed'; accessToken: string }
  | { kind: 'rejected' }
  | { kind: 'unavailable' };

interface TokenPayload {
  accessToken?: string;
  refreshToken?: string;
}

const inFlight = new Map<string, Promise<RefreshResult>>();

/** 여러 탭이 같은 리프레시 토큰을 동시에 회전시키지 않도록 브라우저 전체 락을 잡는다. (미지원 환경은 그대로 실행) */
function withCrossTabLock<T>(task: () => Promise<T>): Promise<T> {
  const locks = typeof navigator !== 'undefined' ? navigator.locks : undefined;
  return locks ? locks.request(REFRESH_LOCK_NAME, task) : task();
}

async function performRefresh(email: string, staleAccessToken: string | null): Promise<RefreshResult> {
  // 락을 기다리는 사이 다른 탭이 이미 갱신했을 수 있으므로 저장소의 최신 상태를 먼저 읽는다.
  useAuthStore.getState().syncFromStorage();
  const account = useAuthStore.getState().accounts.find((acc) => acc.email === email);
  if (!account || !account.refreshToken) {
    return { kind: 'rejected' };
  }
  if (staleAccessToken && account.accessToken !== staleAccessToken) {
    return { kind: 'refreshed', accessToken: account.accessToken };
  }

  try {
    const response = await axios.post(REFRESH_URL, { refreshToken: account.refreshToken });
    const payload: TokenPayload = response.data?.data ?? response.data ?? {};
    if (!payload.accessToken) {
      return { kind: 'unavailable' };
    }
    useAuthStore.getState().updateAccountToken(email, payload.accessToken, payload.refreshToken);
    return { kind: 'refreshed', accessToken: payload.accessToken };
  } catch (error) {
    if (isAxiosError(error) && error.response && REJECTED_STATUSES.has(error.response.status)) {
      return { kind: 'rejected' };
    }
    // 네트워크 오류·타임아웃·5xx 는 일시적 장애이므로 로그아웃하지 않고 호출자가 원래 오류를 전달하게 한다.
    return { kind: 'unavailable' };
  }
}

/**
 * 계정(이메일) 하나의 액세스 토큰을 갱신한다. 같은 탭의 동시 요청은 하나로 합치고,
 * 여러 탭은 락으로 직렬화한다. staleAccessToken 은 실패한 요청이 사용한 토큰이다.
 */
export function refreshAccessToken(email: string, staleAccessToken: string | null): Promise<RefreshResult> {
  const existing = inFlight.get(email);
  if (existing) {
    return existing;
  }
  const promise = withCrossTabLock(() => performRefresh(email, staleAccessToken)).finally(() => {
    inFlight.delete(email);
  });
  inFlight.set(email, promise);
  return promise;
}

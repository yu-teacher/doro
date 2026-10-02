import axios from 'axios';
import { AuthAccount } from '../types/auth';
import { parseJwtPayload } from '../utils/jwtUtils';
import { refreshAccessToken } from './tokenRefresh';

const LOGOUT_URL = '/api/v1/auth/logout';
const LOGOUT_TIMEOUT_MS = 5000;
const MS_PER_SECOND = 1000;

export type LogoutFailureReason = 'no-session' | 'token-expired' | 'request-failed';

export interface LogoutFailure {
  email: string;
  reason: LogoutFailureReason;
}

function isExpired(accessToken: string): boolean {
  const exp = parseJwtPayload(accessToken)?.exp;
  return typeof exp === 'number' && exp * MS_PER_SECOND <= Date.now();
}

async function revokeOne(account: AuthAccount): Promise<LogoutFailure | null> {
  const sessionId = account.sessionId || parseJwtPayload(account.accessToken)?.sid;
  if (!sessionId) {
    return { email: account.email, reason: 'no-session' };
  }

  let accessToken = account.accessToken;
  if (isExpired(accessToken)) {
    // 만료된 토큰이면 해당 계정의 리프레시 토큰으로 한 번만 갱신을 시도한다.
    const refreshed = await refreshAccessToken(account.email, accessToken);
    if (refreshed.kind !== 'refreshed') {
      return { email: account.email, reason: 'token-expired' };
    }
    accessToken = refreshed.accessToken;
  }

  try {
    // apiClient 는 활성 계정 토큰을 덮어쓰므로, 계정별 토큰을 쓰기 위해 axios 를 직접 사용한다.
    await axios.post(LOGOUT_URL, null, {
      params: { sessionId },
      headers: { Authorization: `Bearer ${accessToken}` },
      timeout: LOGOUT_TIMEOUT_MS,
    });
    return null;
  } catch (error: unknown) {
    const status = axios.isAxiosError(error) ? error.response?.status : undefined;
    console.error('Server session logout failed', { status: status ?? 'network' });
    return { email: account.email, reason: 'request-failed' };
  }
}

/**
 * 저장된 모든 계정의 서버 세션을 각 계정 자신의 액세스 토큰으로 종료한다.
 * 실패해도 예외를 던지지 않으며(로컬 상태 정리를 막지 않기 위해), 실패한 계정 목록을 반환해 호출자가 사용자에게 알리게 한다.
 */
export async function revokeAllServerSessions(accounts: AuthAccount[]): Promise<LogoutFailure[]> {
  const results = await Promise.all(accounts.map((account) => revokeOne(account)));
  return results.filter((result): result is LogoutFailure => result !== null);
}

export function describeLogoutFailures(failures: LogoutFailure[]): string | null {
  if (failures.length === 0) {
    return null;
  }
  const emails = failures.map((failure) => failure.email).join(', ');
  return `일부 계정(${emails})의 서버 세션을 종료하지 못했습니다. 이 기기에서는 로그아웃되었지만, 필요하면 해당 계정의 세션 관리에서 다른 기기의 로그인을 확인해 주세요.`;
}

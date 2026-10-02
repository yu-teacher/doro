import axios, { AxiosError } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../store/authStore';
import { AuthAccount } from '../types/auth';
import { describeLogoutFailures, revokeAllServerSessions } from './logoutAll';

const STORAGE_KEY = 'doro_auth_accounts';
const HOUR_SECONDS = 3600;

function b64url(value: object): string {
  return btoa(JSON.stringify(value)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function jwt(payload: object): string {
  return `${b64url({ alg: 'RS256' })}.${b64url(payload)}.sig`;
}

function validToken(sid?: string): string {
  return jwt({ exp: Math.floor(Date.now() / 1000) + HOUR_SECONDS, ...(sid ? { sid } : {}) });
}

function expiredToken(): string {
  return jwt({ exp: Math.floor(Date.now() / 1000) - HOUR_SECONDS });
}

function account(email: string, accessToken: string, sessionId?: string): AuthAccount {
  return { userId: `id-${email}`, email, fullName: email, accessToken, refreshToken: `refresh-${email}`, sessionId, userIndex: 0 };
}

function seed(accounts: AuthAccount[]): void {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(accounts));
  localStorage.setItem('doro_active_account_index', '0');
  useAuthStore.getState().syncFromStorage();
}

describe('revokeAllServerSessions', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('모든 계정에 대해 각자의 토큰과 sessionId 로 로그아웃을 호출한다', async () => {
    const a = account('a@doro.test', validToken(), 'sess-a');
    const b = account('b@doro.test', validToken('sid-from-claim'));
    seed([a, b]);
    const post = vi.spyOn(axios, 'post').mockResolvedValue({ data: { success: true } });

    const failures = await revokeAllServerSessions([a, b]);

    expect(failures).toEqual([]);
    expect(post).toHaveBeenCalledTimes(2);
    const calls = post.mock.calls.map(([url, , config]) => ({
      url,
      sessionId: (config?.params as { sessionId: string }).sessionId,
      auth: (config?.headers as { Authorization: string }).Authorization,
    }));
    expect(calls).toContainEqual({ url: '/api/v1/auth/logout', sessionId: 'sess-a', auth: `Bearer ${a.accessToken}` });
    expect(calls).toContainEqual({ url: '/api/v1/auth/logout', sessionId: 'sid-from-claim', auth: `Bearer ${b.accessToken}` });
  });

  it('액세스 토큰이 만료된 계정은 한 번 갱신한 새 토큰으로 호출한다', async () => {
    const a = account('a@doro.test', expiredToken(), 'sess-a');
    seed([a]);
    const fresh = validToken();
    const post = vi.spyOn(axios, 'post').mockImplementation(async (url: string) => {
      if (url === '/api/v1/auth/token/refresh') {
        return { data: { data: { accessToken: fresh, refreshToken: 'r2' } } };
      }
      return { data: { success: true } };
    });

    const failures = await revokeAllServerSessions([a]);

    expect(failures).toEqual([]);
    const logoutCall = post.mock.calls.find(([url]) => url === '/api/v1/auth/logout');
    expect((logoutCall?.[2]?.headers as { Authorization: string }).Authorization).toBe(`Bearer ${fresh}`);
  });

  it('갱신이 거부되면 해당 계정만 실패로 보고하고 다른 계정은 계속 처리한다', async () => {
    const a = account('a@doro.test', expiredToken(), 'sess-a');
    const b = account('b@doro.test', validToken(), 'sess-b');
    seed([a, b]);
    const rejected = new AxiosError('x', 'ERR_BAD_REQUEST', undefined, undefined, {
      status: 401, statusText: '', data: {}, headers: {}, config: { headers: {} } as never,
    });
    const post = vi.spyOn(axios, 'post').mockImplementation(async (url: string) => {
      if (url === '/api/v1/auth/token/refresh') throw rejected;
      return { data: { success: true } };
    });

    const failures = await revokeAllServerSessions([a, b]);

    expect(failures).toEqual([{ email: 'a@doro.test', reason: 'token-expired' }]);
    const logoutCalls = post.mock.calls.filter(([url]) => url === '/api/v1/auth/logout');
    expect(logoutCalls).toHaveLength(1);
  });

  it('요청 실패는 예외 없이 실패 목록으로 반환되고 console.error 로 기록된다(토큰은 로그에 없음)', async () => {
    const a = account('a@doro.test', validToken(), 'sess-a');
    const b = account('b@doro.test', validToken(), 'sess-b');
    seed([a, b]);
    vi.spyOn(axios, 'post').mockImplementation(async (_url: string, _data, config) => {
      if ((config?.params as { sessionId: string }).sessionId === 'sess-a') throw new Error('Network Error');
      return { data: { success: true } };
    });
    const errorSpy = vi.spyOn(console, 'error');

    const failures = await revokeAllServerSessions([a, b]);

    expect(failures).toEqual([{ email: 'a@doro.test', reason: 'request-failed' }]);
    expect(errorSpy).toHaveBeenCalled();
    expect(JSON.stringify(errorSpy.mock.calls)).not.toContain(a.accessToken);
  });

  it('세션 ID 를 알 수 없으면 no-session 으로 보고한다', async () => {
    const a = account('a@doro.test', validToken());
    seed([a]);
    const post = vi.spyOn(axios, 'post');

    expect(await revokeAllServerSessions([a])).toEqual([{ email: 'a@doro.test', reason: 'no-session' }]);
    expect(post).not.toHaveBeenCalled();
  });

  it('describeLogoutFailures 는 실패가 없으면 null, 있으면 계정을 포함한 안내 문구', () => {
    expect(describeLogoutFailures([])).toBeNull();
    expect(describeLogoutFailures([{ email: 'a@doro.test', reason: 'request-failed' }])).toContain('a@doro.test');
  });
});

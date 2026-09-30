import axios, { AxiosError } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../store/authStore';
import { AuthAccount } from '../types/auth';
import { refreshAccessToken } from './tokenRefresh';

const STORAGE_KEY = 'doro_auth_accounts';

function account(email: string, accessToken: string, refreshToken: string): AuthAccount {
  return { userId: `id-${email}`, email, fullName: email, accessToken, refreshToken, userIndex: 0 };
}

function seed(accounts: AuthAccount[]): void {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(accounts));
  localStorage.setItem('doro_active_account_index', '0');
  useAuthStore.getState().syncFromStorage();
}

function httpError(status: number): AxiosError {
  return new AxiosError('failed', 'ERR_BAD_RESPONSE', undefined, undefined, {
    status,
    statusText: '',
    data: {},
    headers: {},
    config: { headers: {} } as never,
  });
}

describe('refreshAccessToken', () => {
  beforeEach(() => {
    localStorage.clear();
    seed([account('a@doro.test', 'old-a', 'refresh-a'), account('b@doro.test', 'old-b', 'refresh-b')]);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('갱신에 성공하면 해당 계정의 토큰만 교체한다', async () => {
    vi.spyOn(axios, 'post').mockResolvedValue({ data: { data: { accessToken: 'new-a', refreshToken: 'refresh-a2' } } });

    const result = await refreshAccessToken('a@doro.test', 'old-a');

    expect(result).toEqual({ kind: 'refreshed', accessToken: 'new-a' });
    const [a, b] = useAuthStore.getState().accounts;
    expect(a).toMatchObject({ accessToken: 'new-a', refreshToken: 'refresh-a2' });
    expect(b).toMatchObject({ accessToken: 'old-b', refreshToken: 'refresh-b' });
  });

  it('네트워크 오류는 rejected 가 아니라 unavailable 이고 계정은 유지된다', async () => {
    vi.spyOn(axios, 'post').mockRejectedValue(new Error('Network Error'));

    const result = await refreshAccessToken('a@doro.test', 'old-a');

    expect(result).toEqual({ kind: 'unavailable' });
    expect(useAuthStore.getState().accounts).toHaveLength(2);
  });

  it('서버 5xx 도 unavailable 이다', async () => {
    vi.spyOn(axios, 'post').mockRejectedValue(httpError(503));

    expect(await refreshAccessToken('a@doro.test', 'old-a')).toEqual({ kind: 'unavailable' });
  });

  it.each([400, 401, 403])('서버가 리프레시 토큰을 거부(%i)하면 rejected 다', async (status) => {
    vi.spyOn(axios, 'post').mockRejectedValue(httpError(status));

    expect(await refreshAccessToken('a@doro.test', 'old-a')).toEqual({ kind: 'rejected' });
  });

  it('다른 탭이 이미 갱신했다면(저장된 토큰이 실패한 토큰과 다름) 다시 회전하지 않고 그 토큰을 쓴다', async () => {
    seed([account('a@doro.test', 'already-refreshed', 'refresh-a2'), account('b@doro.test', 'old-b', 'refresh-b')]);
    const post = vi.spyOn(axios, 'post');

    const result = await refreshAccessToken('a@doro.test', 'old-a');

    expect(result).toEqual({ kind: 'refreshed', accessToken: 'already-refreshed' });
    expect(post).not.toHaveBeenCalled();
  });

  it('같은 계정의 동시 갱신 요청은 한 번의 네트워크 호출로 합쳐진다', async () => {
    const post = vi.spyOn(axios, 'post').mockResolvedValue({ data: { data: { accessToken: 'new-a', refreshToken: 'r2' } } });

    const results = await Promise.all([
      refreshAccessToken('a@doro.test', 'old-a'),
      refreshAccessToken('a@doro.test', 'old-a'),
      refreshAccessToken('a@doro.test', 'old-a'),
    ]);

    expect(post).toHaveBeenCalledTimes(1);
    expect(results.every((r) => r.kind === 'refreshed')).toBe(true);
  });

  it('응답에 액세스 토큰이 없으면 로그아웃하지 않고 unavailable 이다', async () => {
    vi.spyOn(axios, 'post').mockResolvedValue({ data: {} });

    expect(await refreshAccessToken('a@doro.test', 'old-a')).toEqual({ kind: 'unavailable' });
  });
});

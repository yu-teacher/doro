import axios, { AxiosError } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clearAccounts, seedAccounts } from '../test-support/seedAccounts';
import { useAuthStore } from '../store/authStore';
import { AuthAccount } from '../types/auth';
import { refreshAccessToken } from './tokenRefresh';

function account(email: string, accessToken: string, slot: number): AuthAccount {
  return { userId: `id-${email}`, email, fullName: email, accessToken, slot, userIndex: 0 };
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
    seedAccounts([account('a@doro.test', 'old-a', 0), account('b@doro.test', 'old-b', 1)]);
  });

  afterEach(() => {
    vi.restoreAllMocks();
    clearAccounts();
  });

  it('갱신에 성공하면 해당 계정의 토큰만 교체하고, 쿠키 방식 헤더와 그 계정의 슬롯을 보낸다', async () => {
    const post = vi.spyOn(axios, 'post').mockResolvedValue({ data: { data: { accessToken: 'new-b' } } });

    const result = await refreshAccessToken('b@doro.test', 'old-b');

    expect(result).toEqual({ kind: 'refreshed', accessToken: 'new-b' });
    const [a, b] = useAuthStore.getState().accounts;
    expect(a.accessToken).toBe('old-a');
    expect(b.accessToken).toBe('new-b');
    expect(post).toHaveBeenCalledWith('/api/v1/auth/token/refresh', {}, { headers: { 'X-Doro-Cookie-Session': '1', 'X-Doro-Account-Slot': '1' } });
  });

  it('리프레시 토큰은 요청 본문에 없다(HttpOnly 쿠키로만 전달된다)', async () => {
    const post = vi.spyOn(axios, 'post').mockResolvedValue({ data: { data: { accessToken: 'new-a' } } });

    await refreshAccessToken('a@doro.test', 'old-a');

    expect(JSON.stringify(post.mock.calls[0][1])).not.toContain('refreshToken');
  });

  it('예전 방식으로 저장된 리프레시 토큰이 남은 계정은 한 번 본문으로 보내 쿠키로 옮기고, 성공하면 저장소에서 지운다', async () => {
    localStorage.setItem('doro_auth_accounts', JSON.stringify([{ userId: 'u', email: 'a@doro.test', fullName: 'A', refreshToken: 'legacy-refresh', userIndex: 0 }]));
    localStorage.setItem('doro_active_account_index', '0');
    useAuthStore.getState().syncFromStorage();
    const post = vi.spyOn(axios, 'post').mockResolvedValue({ data: { data: { accessToken: 'new-a' } } });

    const result = await refreshAccessToken('a@doro.test', null);

    expect(result).toEqual({ kind: 'refreshed', accessToken: 'new-a' });
    expect(post.mock.calls[0][1]).toEqual({ refreshToken: 'legacy-refresh' });
    expect(localStorage.getItem('doro_auth_accounts')).not.toContain('legacy-refresh');
    expect(useAuthStore.getState().accounts[0].refreshToken).toBeUndefined();
  });

  it('옮기는 요청이 일시적으로 실패하면(네트워크) 예전 토큰을 지우지 않아 다음에 다시 옮길 수 있다', async () => {
    localStorage.setItem('doro_auth_accounts', JSON.stringify([{ userId: 'u', email: 'a@doro.test', fullName: 'A', refreshToken: 'legacy-refresh', userIndex: 0 }]));
    useAuthStore.getState().syncFromStorage();
    vi.spyOn(axios, 'post').mockRejectedValue(new Error('Network Error'));

    expect(await refreshAccessToken('a@doro.test', null)).toEqual({ kind: 'unavailable' });

    expect(localStorage.getItem('doro_auth_accounts')).toContain('legacy-refresh');
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

  it.each([400, 401, 403])('서버가 리프레시 토큰(쿠키)을 거부(%i)하면 rejected 다', async (status) => {
    vi.spyOn(axios, 'post').mockRejectedValue(httpError(status));

    expect(await refreshAccessToken('a@doro.test', 'old-a')).toEqual({ kind: 'rejected' });
  });

  it('이 탭의 다른 요청이 이미 갱신했다면(현재 토큰이 실패한 토큰과 다름) 다시 회전하지 않고 그 토큰을 쓴다', async () => {
    useAuthStore.getState().updateAccountToken('a@doro.test', 'already-refreshed');
    const post = vi.spyOn(axios, 'post');

    const result = await refreshAccessToken('a@doro.test', 'old-a');

    expect(result).toEqual({ kind: 'refreshed', accessToken: 'already-refreshed' });
    expect(post).not.toHaveBeenCalled();
  });

  it('같은 계정의 동시 갱신 요청은 한 번의 네트워크 호출로 합쳐진다', async () => {
    const post = vi.spyOn(axios, 'post').mockResolvedValue({ data: { data: { accessToken: 'new-a' } } });

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

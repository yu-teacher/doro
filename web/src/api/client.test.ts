import axios, { AxiosError, InternalAxiosRequestConfig } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../store/authStore';
import { clearAccounts, seedAccounts } from '../test-support/seedAccounts';
import { apiClient } from './client';

function seedOneAccount(accessToken = 'expired'): void {
  seedAccounts([{ userId: 'u1', email: 'a@doro.test', fullName: 'A', accessToken, slot: 0, userIndex: 0 }]);
}

function unauthorized(config: InternalAxiosRequestConfig): AxiosError {
  return new AxiosError('Unauthorized', 'ERR_BAD_REQUEST', config, undefined, {
    status: 401, statusText: 'Unauthorized', data: {}, headers: {}, config,
  });
}

describe('apiClient 401 처리', () => {
  const seenTokens: string[] = [];

  beforeEach(() => {
    seenTokens.length = 0;
    seedOneAccount();
    apiClient.defaults.adapter = async (config) => {
      const header = config.headers.get('Authorization');
      seenTokens.push(String(header));
      if (header === 'Bearer fresh') {
        return { data: { ok: true }, status: 200, statusText: 'OK', headers: {}, config };
      }
      throw unauthorized(config);
    };
  });

  afterEach(() => {
    vi.restoreAllMocks();
    clearAccounts();
  });

  it('401 이면 토큰을 갱신하고 같은 요청을 새 토큰으로 재시도한다', async () => {
    vi.spyOn(axios, 'post').mockResolvedValue({ data: { data: { accessToken: 'fresh' } } });

    const response = await apiClient.get('/api/v1/users/me');

    expect(response.data).toEqual({ ok: true });
    expect(seenTokens).toEqual(['Bearer expired', 'Bearer fresh']);
    expect(useAuthStore.getState().accounts[0]).toMatchObject({ accessToken: 'fresh' });
  });

  it('갱신 중 네트워크 오류가 나도 로그인 상태(계정)를 잃지 않고 원래 오류를 전달한다', async () => {
    vi.spyOn(axios, 'post').mockRejectedValue(new Error('Network Error'));

    await expect(apiClient.get('/api/v1/users/me')).rejects.toMatchObject({ response: { status: 401 } });

    expect(useAuthStore.getState().accounts).toHaveLength(1);
    expect(JSON.parse(localStorage.getItem('doro_auth_accounts') ?? '[]')).toHaveLength(1);
  });

  it('공개 인증 엔드포인트(로그인)의 401 은 토큰 갱신을 시도하지 않는다', async () => {
    const post = vi.spyOn(axios, 'post');

    await expect(apiClient.post('/api/v1/auth/login', {})).rejects.toBeDefined();

    expect(post).not.toHaveBeenCalled();
  });

  it('새로고침 직후(메모리에 토큰 없음)에는 쿠키로 토큰을 먼저 받은 뒤 첫 요청부터 그 토큰으로 보낸다', async () => {
    seedOneAccount('');
    const post = vi.spyOn(axios, 'post').mockResolvedValue({ data: { data: { accessToken: 'fresh' } } });

    const response = await apiClient.get('/api/v1/users/me');

    expect(response.data).toEqual({ ok: true });
    expect(seenTokens).toEqual(['Bearer fresh']);
    expect(post).toHaveBeenCalledTimes(1);
    expect(post.mock.calls[0][2]?.headers).toMatchObject({ 'X-Doro-Cookie-Session': '1', 'X-Doro-Account-Slot': '0' });
  });

  it('새로고침 직후 서버가 쿠키를 거부하면 로그인 상태를 정리하고 요청은 보내지 않는다', async () => {
    seedOneAccount('');
    const rejected = new AxiosError('x', 'ERR_BAD_REQUEST', undefined, undefined, {
      status: 401, statusText: '', data: {}, headers: {}, config: { headers: {} } as never,
    });
    vi.spyOn(axios, 'post').mockRejectedValue(rejected);
    const location = vi.spyOn(window, 'location', 'get').mockReturnValue({ ...window.location, href: '' } as Location);

    await expect(apiClient.get('/api/v1/users/me')).rejects.toMatchObject({ code: 'ERR_SESSION_EXPIRED' });

    expect(seenTokens).toEqual([]);
    expect(useAuthStore.getState().accounts).toHaveLength(0);
    location.mockRestore();
  });

  it('로그인 요청은 토큰이 없어도 갱신을 먼저 시도하지 않는다', async () => {
    seedOneAccount('');
    const post = vi.spyOn(axios, 'post');
    apiClient.defaults.adapter = async (config) => ({ data: { ok: true }, status: 200, statusText: 'OK', headers: {}, config });

    await apiClient.post('/api/v1/auth/login', {});

    expect(post).not.toHaveBeenCalled();
  });
});

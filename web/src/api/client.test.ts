import axios, { AxiosError, InternalAxiosRequestConfig } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../store/authStore';
import { apiClient } from './client';

const STORAGE_KEY = 'doro_auth_accounts';

function seedOneAccount(): void {
  localStorage.setItem(
    STORAGE_KEY,
    JSON.stringify([{ userId: 'u1', email: 'a@doro.test', fullName: 'A', accessToken: 'expired', refreshToken: 'r1', userIndex: 0 }]),
  );
  localStorage.setItem('doro_active_account_index', '0');
  useAuthStore.getState().syncFromStorage();
}

function unauthorized(config: InternalAxiosRequestConfig): AxiosError {
  return new AxiosError('Unauthorized', 'ERR_BAD_REQUEST', config, undefined, {
    status: 401, statusText: 'Unauthorized', data: {}, headers: {}, config,
  });
}

describe('apiClient 401 처리', () => {
  const seenTokens: string[] = [];

  beforeEach(() => {
    localStorage.clear();
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
  });

  it('401 이면 토큰을 갱신하고 같은 요청을 새 토큰으로 재시도한다', async () => {
    vi.spyOn(axios, 'post').mockResolvedValue({ data: { data: { accessToken: 'fresh', refreshToken: 'r2' } } });

    const response = await apiClient.get('/api/v1/users/me');

    expect(response.data).toEqual({ ok: true });
    expect(seenTokens).toEqual(['Bearer expired', 'Bearer fresh']);
    expect(useAuthStore.getState().accounts[0]).toMatchObject({ accessToken: 'fresh', refreshToken: 'r2' });
  });

  it('갱신 중 네트워크 오류가 나도 로그인 상태(계정)를 잃지 않고 원래 오류를 전달한다', async () => {
    vi.spyOn(axios, 'post').mockRejectedValue(new Error('Network Error'));

    await expect(apiClient.get('/api/v1/users/me')).rejects.toMatchObject({ response: { status: 401 } });

    expect(useAuthStore.getState().accounts).toHaveLength(1);
    expect(JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '[]')).toHaveLength(1);
  });

  it('공개 인증 엔드포인트(로그인)의 401 은 토큰 갱신을 시도하지 않는다', async () => {
    const post = vi.spyOn(axios, 'post');

    await expect(apiClient.post('/api/v1/auth/login', {})).rejects.toBeDefined();

    expect(post).not.toHaveBeenCalled();
  });
});

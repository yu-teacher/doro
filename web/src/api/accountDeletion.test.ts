import { AxiosError, InternalAxiosRequestConfig } from 'axios';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { useAuthStore } from '../store/authStore';
import { authApi } from './authApi';
import { clearAccounts, seedAccounts } from '../test-support/seedAccounts';
import { apiClient } from './client';

const DELETION_PATH = '/api/v1/users/me/deletion';
const PROFILE_PATH = '/api/v1/users/me';

function seedOneAccount(): void {
  seedAccounts([{ userId: 'u1', email: 'a@doro.test', fullName: 'A', accessToken: 'valid', slot: 0, userIndex: 0 }]);
}

function rejection(config: InternalAxiosRequestConfig, status: number, code: string): AxiosError {
  return new AxiosError('failed', 'ERR_BAD_REQUEST', config, undefined, {
    status, statusText: 'err', data: { success: false, code, message: '비밀번호가 일치하지 않습니다.' }, headers: {}, config,
  });
}

describe('회원탈퇴 요청 API', () => {
  const calls: string[] = [];

  afterEach(() => {
    clearAccounts();
  });

  beforeEach(() => {
    calls.length = 0;
    seedOneAccount();
  });

  it('먼저 토큰을 최신으로 만든 뒤 탈퇴를 한 번 요청하고 처리 예정 시각을 돌려준다', async () => {
    apiClient.defaults.adapter = async (config) => {
      calls.push(`${config.method?.toUpperCase()} ${config.url}`);
      const data = config.url === DELETION_PATH
        ? { success: true, data: { scheduledPurgeAt: '2026-11-06T01:00:00Z' } }
        : { success: true, data: {} };
      return { data, status: 200, statusText: 'OK', headers: {}, config };
    };

    const result = await authApi.requestAccountDeletion({ password: 'Password123!' });

    expect(result.scheduledPurgeAt).toBe('2026-11-06T01:00:00Z');
    expect(calls).toEqual([`GET ${PROFILE_PATH}`, `POST ${DELETION_PATH}`]);
  });

  it('비밀번호가 틀려 401 이 와도 다시 보내지 않고(실패가 잠금 횟수에 두 번 합산되지 않게) 로그인 상태도 유지한다', async () => {
    apiClient.defaults.adapter = async (config) => {
      calls.push(`${config.method?.toUpperCase()} ${config.url}`);
      if (config.url === DELETION_PATH) {
        throw rejection(config, 401, 'INVALID_CREDENTIALS');
      }
      return { data: { success: true, data: {} }, status: 200, statusText: 'OK', headers: {}, config };
    };

    await expect(authApi.requestAccountDeletion({ password: 'wrong' })).rejects.toMatchObject({
      response: { status: 401 },
    });

    expect(calls.filter((c) => c === `POST ${DELETION_PATH}`)).toHaveLength(1);
    expect(useAuthStore.getState().accounts).toHaveLength(1);
  });
});

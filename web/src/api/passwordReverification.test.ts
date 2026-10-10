import { AxiosError, InternalAxiosRequestConfig } from 'axios';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { useAuthStore } from '../store/authStore';
import { authApi } from './authApi';
import { clearAccounts, seedAccounts } from '../test-support/seedAccounts';
import { apiClient } from './client';

const PROFILE_PATH = '/api/v1/users/me';
const PASSWORD_PATH = '/api/v1/users/me/password';
const SETUP_2FA_PATH = '/api/v1/auth/2fa/setup';

function seedOneAccount(): void {
  seedAccounts([{ userId: 'u1', email: 'a@doro.test', fullName: 'A', accessToken: 'valid', slot: 0, userIndex: 0 }]);
}

function rejection(config: InternalAxiosRequestConfig, status: number, code: string): AxiosError {
  return new AxiosError('failed', 'ERR_BAD_REQUEST', config, undefined, {
    status, statusText: 'err', data: { success: false, code, message: '비밀번호가 일치하지 않습니다.' }, headers: {}, config,
  });
}

/** 비밀번호를 다시 확인하는 요청은 실패가 서버의 잠금 횟수에 합산되므로, 401 이어도 같은 요청을 다시 보내면 안 된다. */
describe.each([
  ['비밀번호 변경', PASSWORD_PATH, () => authApi.changePassword({ currentPassword: 'wrong', newPassword: 'NewPassword456!' })],
  ['2FA 등록 시작', SETUP_2FA_PATH, () => authApi.setup2fa('wrong')],
])('%s', (_name, path, call) => {
  const calls: string[] = [];

  beforeEach(() => {
    calls.length = 0;
    seedOneAccount();
  });

  afterEach(() => {
    clearAccounts();
  });

  it('먼저 토큰을 최신으로 만든 뒤 요청을 한 번만 보낸다', async () => {
    apiClient.defaults.adapter = async (config) => {
      calls.push(`${config.method?.toUpperCase()} ${config.url}`);
      return { data: { success: true, data: { secret: 's', qrUri: 'q' } }, status: 200, statusText: 'OK', headers: {}, config };
    };

    await call();

    expect(calls).toEqual([`GET ${PROFILE_PATH}`, `${path === PASSWORD_PATH ? 'PUT' : 'POST'} ${path}`]);
  });

  it('비밀번호가 틀려 401 이 와도 다시 보내지 않고 로그인 상태를 유지한다', async () => {
    apiClient.defaults.adapter = async (config) => {
      calls.push(`${config.method?.toUpperCase()} ${config.url}`);
      if (config.url === path) {
        throw rejection(config, 401, 'INVALID_CREDENTIALS');
      }
      return { data: { success: true, data: {} }, status: 200, statusText: 'OK', headers: {}, config };
    };

    await expect(call()).rejects.toMatchObject({ response: { status: 401 } });

    expect(calls.filter((c) => c.endsWith(path))).toHaveLength(1);
    expect(useAuthStore.getState().accounts).toHaveLength(1);
  });
});

import axios from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clearAccounts, seedAccounts } from '../test-support/seedAccounts';
import { authApi } from './authApi';
import { apiClient } from './client';
import { refreshAccessToken } from './tokenRefresh';

/** navigator.locks 처럼 같은 이름의 작업을 차례로 실행하는 가짜 락 */
function installSerialLocks() {
  const names: string[] = [];
  let chain: Promise<unknown> = Promise.resolve();
  const locks = {
    request: (name: string, task: () => Promise<unknown>) => {
      names.push(name);
      const run = chain.then(() => task());
      chain = run.catch(() => undefined);
      return run;
    },
  };
  Object.defineProperty(navigator, 'locks', { value: locks, configurable: true });
  return names;
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => { resolve = r; });
  return { promise, resolve };
}

describe('리프레시 엔드포인트 공용 락', () => {
  beforeEach(() => {
    seedAccounts([{ userId: 'id-a', email: 'a@doro.test', fullName: 'a', accessToken: 'old-a', slot: 0, userIndex: 0 }]);
  });

  afterEach(() => {
    vi.restoreAllMocks();
    clearAccounts();
    Reflect.deleteProperty(navigator, 'locks');
  });

  it('소셜 로그인 복귀 갱신과 일반 갱신은 같은 락을 잡고 차례로 나간다(같은 쿠키를 동시에 회전시키지 않는다)', async () => {
    const names = installSerialLocks();
    const first = deferred<{ data: { data: { accessToken: string } } }>();
    const socialPost = vi.spyOn(apiClient, 'post').mockReturnValueOnce(first.promise as never);
    const refreshPost = vi.spyOn(axios, 'post').mockResolvedValueOnce({ data: { data: { accessToken: 'new-a' } } });

    const social = authApi.refreshFromCookie(0);
    const normal = refreshAccessToken('a@doro.test', null);
    await Promise.resolve();
    await Promise.resolve();

    expect(names).toEqual(['doro-token-refresh', 'doro-token-refresh']);
    expect(socialPost).toHaveBeenCalledTimes(1);
    expect(refreshPost).not.toHaveBeenCalled(); // 앞선 갱신이 끝나기 전에는 보내지 않는다

    first.resolve({ data: { data: { accessToken: 'social-a' } } });
    await expect(social).resolves.toEqual({ accessToken: 'social-a' });
    await expect(normal).resolves.toEqual({ kind: 'refreshed', accessToken: 'new-a' });
    expect(refreshPost).toHaveBeenCalledTimes(1);
  });
});

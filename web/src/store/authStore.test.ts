import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { clearAccounts, seedAccounts } from '../test-support/seedAccounts';
import { useAuthStore } from './authStore';
import { AuthAccount } from '../types/auth';

const STORAGE_KEY = 'doro_auth_accounts';

function account(email: string, slot: number, accessToken = 'secret-access-token'): AuthAccount {
  return { userId: `id-${email}`, email, fullName: email, accessToken, slot, userIndex: 0 };
}

describe('계정 저장소: 토큰은 localStorage 에 쓰지 않는다', () => {
  beforeEach(() => clearAccounts());
  afterEach(() => clearAccounts());

  it('저장소에는 액세스 토큰도 리프레시 토큰도 남지 않고, 메모리에는 액세스 토큰이 있다', () => {
    seedAccounts([account('a@doro.test', 0)]);

    const raw = localStorage.getItem(STORAGE_KEY) ?? '';
    expect(raw).toContain('a@doro.test');
    expect(raw).not.toContain('secret-access-token');
    expect(raw).not.toMatch(/accessToken|refreshToken/);
    expect(useAuthStore.getState().accounts[0].accessToken).toBe('secret-access-token');
  });

  it('프로필·토큰 갱신·계정 전환 등 어떤 저장 경로에서도 토큰이 새어 나가지 않는다', () => {
    seedAccounts([account('a@doro.test', 0), account('b@doro.test', 1)]);
    const store = useAuthStore.getState();

    store.updateActiveProfile('새 이름', 'https://img.example/x.png', 'ADMIN');
    store.updateAccountToken('a@doro.test', 'another-secret-token');
    store.switchAccount(0);
    store.removeAccount(1);

    expect(localStorage.getItem(STORAGE_KEY)).not.toMatch(/secret|accessToken|refreshToken/);
  });

  it('새로고침(저장소에서 다시 읽기)해도 계정 목록과 슬롯은 남는다', () => {
    seedAccounts([account('a@doro.test', 0)]);
    // 새로고침을 흉내 낸다: 화면 상태를 비우고 저장소만 다시 읽는다
    useAuthStore.setState({ accounts: [], activeAccountIndex: 0 });
    localStorage.setItem('doro_active_account_index', '0');
    useAuthStore.getState().syncFromStorage();

    const [loaded] = useAuthStore.getState().accounts;
    expect(loaded.email).toBe('a@doro.test');
    expect(loaded.slot).toBe(0);
  });

  it('슬롯: 이미 있는 계정은 그 슬롯, 새 계정은 빈 슬롯 중 가장 작은 것, 다 차면 null', () => {
    seedAccounts([account('a@doro.test', 0), account('b@doro.test', 2)]);
    const store = useAuthStore.getState();

    expect(store.slotFor('b@doro.test')).toBe(2);
    expect(store.slotFor('new@doro.test')).toBe(1);

    seedAccounts([0, 1, 2, 3, 4].map((slot) => account(`u${slot}@doro.test`, slot)));
    expect(useAuthStore.getState().slotFor('extra@doro.test')).toBeNull();
    expect(useAuthStore.getState().slotFor('u3@doro.test')).toBe(3);
  });

  it('예전 방식으로 저장된 계정(슬롯 없음, 리프레시 토큰 있음)은 슬롯을 받고 토큰은 옮기기 전까지 보존된다', () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify([
      { userId: 'u1', email: 'a@doro.test', fullName: 'A', accessToken: 'old-access', refreshToken: 'legacy-a', userIndex: 0 },
      { userId: 'u2', email: 'b@doro.test', fullName: 'B', accessToken: 'old-access', refreshToken: 'legacy-b', userIndex: 0 },
    ]));
    useAuthStore.getState().syncFromStorage();

    const accounts = useAuthStore.getState().accounts;
    expect(accounts.map((a) => a.slot)).toEqual([0, 1]);
    expect(accounts.map((a) => a.refreshToken)).toEqual(['legacy-a', 'legacy-b']);
    // 예전 액세스 토큰은 메모리로 가져오지 않는다
    expect(accounts.every((a) => a.accessToken === '')).toBe(true);
  });
});

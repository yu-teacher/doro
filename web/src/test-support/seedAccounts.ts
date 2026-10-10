import { useAuthStore } from '../store/authStore';
import { AuthAccount } from '../types/auth';

/**
 * 테스트용 계정 심기. 액세스 토큰은 localStorage 가 아니라 메모리(addAccount)로 들어가므로, 저장소에 직접 쓰지 않고 스토어를 거친다.
 * 이전 테스트의 메모리 토큰이 남지 않도록 먼저 모두 비운다.
 */
export function seedAccounts(accounts: AuthAccount[], activeIndex = 0): void {
  localStorage.clear();
  const store = useAuthStore.getState();
  store.logoutAll();
  accounts.forEach((account) => useAuthStore.getState().addAccount(account));
  useAuthStore.getState().switchAccount(activeIndex);
}

export function clearAccounts(): void {
  localStorage.clear();
  useAuthStore.getState().logoutAll();
}

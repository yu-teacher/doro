import { create } from 'zustand';
import { AuthAccount, UserRole } from '../types/auth';
import { MAX_ACCOUNTS } from '../api/cookieSession';

interface AuthState {
  accounts: AuthAccount[];
  activeAccountIndex: number;

  getActiveAccount: () => AuthAccount | null;
  /** 이 이메일 계정이 쓸 쿠키 슬롯. 이미 있는 계정이면 그 슬롯, 아니면 빈 슬롯. 모두 차 있으면 null. */
  slotFor: (email: string) => number | null;
  addAccount: (account: AuthAccount) => void;
  switchAccount: (index: number) => void;
  updateActiveToken: (accessToken: string) => void;
  /** 특정 계정(이메일)의 액세스 토큰만 바꾼다. 갱신 도중 활성 계정이 바뀌어도 다른 계정을 건드리지 않는다. 예전 방식의 리프레시 토큰은 지운다. */
  updateAccountToken: (email: string, accessToken: string) => void;
  removeAccountByEmail: (email: string) => void;
  /** 다른 탭이 localStorage 에 기록한 계정 목록을 다시 읽는다(이 탭 메모리의 액세스 토큰은 유지한다). */
  syncFromStorage: () => void;
  updateActiveProfile: (fullName?: string, profileImageUrl?: string | null, role?: UserRole) => void;
  removeAccount: (index: number) => void;
  logoutAll: () => void;
}

const STORAGE_KEY = 'doro_auth_accounts';
const ACTIVE_INDEX_KEY = 'doro_active_account_index';

/**
 * 액세스 토큰은 localStorage 에 쓰지 않고 이 탭의 메모리에만 둔다(XSS·같은 도메인의 다른 서비스가 읽어 가지 못하게).
 * 저장되는 것은 계정 목록(이메일·이름·역할·슬롯 등 비밀이 아닌 정보)뿐이다. 새로고침하면 토큰이 비므로 첫 요청이 HttpOnly 쿠키로 다시 받는다.
 */
const memoryTokens = new Map<string, string>();

function usedSlots(accounts: Array<{ slot?: number }>): Set<number> {
  return new Set(accounts.map((a) => a.slot).filter((s): s is number => typeof s === 'number'));
}

function firstFreeSlot(used: Set<number>): number | null {
  for (let slot = 0; slot < MAX_ACCOUNTS; slot += 1) {
    if (!used.has(slot)) return slot;
  }
  return null;
}

function persist(accounts: AuthAccount[]): void {
  const stored = accounts.map((account) => {
    const copy: Partial<AuthAccount> = { ...account };
    delete copy.accessToken;
    return copy;
  });
  localStorage.setItem(STORAGE_KEY, JSON.stringify(stored));
}

/** 저장된 계정 목록. 슬롯이 없던 예전 계정에는 빈 슬롯을 나눠 주고(저장도 한다), 액세스 토큰은 이 탭 메모리의 것을 쓴다. */
const loadAccounts = (): AuthAccount[] => {
  let stored: Array<Partial<AuthAccount>>;
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    stored = raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
  if (!Array.isArray(stored)) return [];

  const used = usedSlots(stored);
  let assigned = false;
  const accounts = stored.map((account) => {
    let slot = account.slot;
    if (typeof slot !== 'number' || slot < 0 || slot >= MAX_ACCOUNTS) {
      slot = firstFreeSlot(used) ?? 0;
      used.add(slot);
      assigned = true;
    }
    return { ...account, slot, accessToken: memoryTokens.get(account.email ?? '') ?? '' } as AuthAccount;
  });
  if (assigned) {
    try {
      persist(accounts);
    } catch {
      // 저장 공간이 막힌 환경이면 이번 탭 동안만 슬롯을 쓴다
    }
  }
  return accounts;
};

const loadActiveIndex = (): number => {
  try {
    const raw = localStorage.getItem(ACTIVE_INDEX_KEY);
    return raw ? parseInt(raw, 10) : 0;
  } catch {
    return 0;
  }
};

export const useAuthStore = create<AuthState>((set, get) => ({
  accounts: loadAccounts(),
  activeAccountIndex: loadActiveIndex(),

  getActiveAccount: () => {
    const { accounts, activeAccountIndex } = get();
    if (accounts.length === 0) return null;
    return accounts[activeAccountIndex] || accounts[0] || null;
  },

  slotFor: (email: string) => {
    const { accounts } = get();
    const existing = accounts.find((a) => a.email === email);
    if (existing) return existing.slot;
    return firstFreeSlot(usedSlots(accounts));
  },

  addAccount: (account: AuthAccount) => {
    set((state) => {
      // 이미 존재하는 계정인지 확인 (이메일 기준)
      const existingIdx = state.accounts.findIndex((a) => a.email === account.email);
      let updatedAccounts: AuthAccount[];
      let targetIndex: number;

      if (existingIdx >= 0) {
        updatedAccounts = [...state.accounts];
        updatedAccounts[existingIdx] = account;
        targetIndex = existingIdx;
      } else {
        updatedAccounts = [...state.accounts, account];
        targetIndex = updatedAccounts.length - 1;
      }

      memoryTokens.set(account.email, account.accessToken);
      persist(updatedAccounts);
      localStorage.setItem(ACTIVE_INDEX_KEY, targetIndex.toString());

      return {
        accounts: updatedAccounts,
        activeAccountIndex: targetIndex,
      };
    });
  },

  switchAccount: (index: number) => {
    set((state) => {
      if (index >= 0 && index < state.accounts.length) {
        localStorage.setItem(ACTIVE_INDEX_KEY, index.toString());
        return { activeAccountIndex: index };
      }
      return state;
    });
  },

  updateActiveToken: (accessToken: string) => {
    const active = get().getActiveAccount();
    if (active) {
      get().updateAccountToken(active.email, accessToken);
    }
  },

  updateAccountToken: (email: string, accessToken: string) => {
    set((state) => {
      memoryTokens.set(email, accessToken);
      const updatedAccounts = state.accounts.map((acc) => {
        if (acc.email !== email) return acc;
        // 쿠키로 옮겨 갔으니 예전 방식의 리프레시 토큰은 지운다
        const updated: AuthAccount = { ...acc, accessToken };
        delete updated.refreshToken;
        return updated;
      });
      persist(updatedAccounts);
      return { accounts: updatedAccounts };
    });
  },

  removeAccountByEmail: (email: string) => {
    const index = get().accounts.findIndex((acc) => acc.email === email);
    if (index >= 0) {
      get().removeAccount(index);
    }
  },

  syncFromStorage: () => {
    const accounts = loadAccounts();
    const activeAccountIndex = Math.min(loadActiveIndex(), Math.max(0, accounts.length - 1));
    set({ accounts, activeAccountIndex });
  },

  updateActiveProfile: (fullName?: string, profileImageUrl?: string | null, role?: UserRole) => {
    set((state) => {
      const active = state.getActiveAccount();
      if (!active) return state;

      const updatedAccounts = state.accounts.map((acc, idx) => {
        if (idx === state.activeAccountIndex) {
          return {
            ...acc,
            fullName: fullName !== undefined ? fullName : acc.fullName,
            profileImageUrl: profileImageUrl !== undefined ? profileImageUrl : acc.profileImageUrl,
            role: role !== undefined ? role : acc.role,
          };
        }
        return acc;
      });

      persist(updatedAccounts);
      return { accounts: updatedAccounts };
    });
  },

  removeAccount: (index: number) => {
    set((state) => {
      const removed = state.accounts[index];
      if (removed) memoryTokens.delete(removed.email);
      const updatedAccounts = state.accounts.filter((_, idx) => idx !== index);
      const newActiveIndex = Math.max(0, Math.min(state.activeAccountIndex, updatedAccounts.length - 1));

      persist(updatedAccounts);
      localStorage.setItem(ACTIVE_INDEX_KEY, newActiveIndex.toString());

      return {
        accounts: updatedAccounts,
        activeAccountIndex: newActiveIndex,
      };
    });
  },

  logoutAll: () => {
    memoryTokens.clear();
    localStorage.removeItem(STORAGE_KEY);
    localStorage.removeItem(ACTIVE_INDEX_KEY);
    set({
      accounts: [],
      activeAccountIndex: 0,
    });
  },
}));

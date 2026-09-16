import { create } from 'zustand';
import { AuthAccount, UserRole } from '../types/auth';

interface AuthState {
  accounts: AuthAccount[];
  activeAccountIndex: number;
  
  getActiveAccount: () => AuthAccount | null;
  addAccount: (account: AuthAccount) => void;
  switchAccount: (index: number) => void;
  updateActiveToken: (accessToken: string, refreshToken?: string) => void;
  updateActiveProfile: (fullName?: string, profileImageUrl?: string | null, role?: UserRole) => void;
  removeAccount: (index: number) => void;
  logoutAll: () => void;
}

const STORAGE_KEY = 'doro_auth_accounts';
const ACTIVE_INDEX_KEY = 'doro_active_account_index';

const loadAccounts = (): AuthAccount[] => {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
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

      localStorage.setItem(STORAGE_KEY, JSON.stringify(updatedAccounts));
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

  updateActiveToken: (accessToken: string, refreshToken?: string) => {
    set((state) => {
      const active = state.getActiveAccount();
      if (!active) return state;

      const updatedAccounts = state.accounts.map((acc, idx) => {
        if (idx === state.activeAccountIndex) {
          return {
            ...acc,
            accessToken,
            refreshToken: refreshToken || acc.refreshToken,
          };
        }
        return acc;
      });

      localStorage.setItem(STORAGE_KEY, JSON.stringify(updatedAccounts));
      return { accounts: updatedAccounts };
    });
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

      localStorage.setItem(STORAGE_KEY, JSON.stringify(updatedAccounts));
      return { accounts: updatedAccounts };
    });
  },

  removeAccount: (index: number) => {
    set((state) => {
      const updatedAccounts = state.accounts.filter((_, idx) => idx !== index);
      const newActiveIndex = Math.max(0, Math.min(state.activeAccountIndex, updatedAccounts.length - 1));

      localStorage.setItem(STORAGE_KEY, JSON.stringify(updatedAccounts));
      localStorage.setItem(ACTIVE_INDEX_KEY, newActiveIndex.toString());

      return {
        accounts: updatedAccounts,
        activeAccountIndex: newActiveIndex,
      };
    });
  },

  logoutAll: () => {
    localStorage.removeItem(STORAGE_KEY);
    localStorage.removeItem(ACTIVE_INDEX_KEY);
    set({
      accounts: [],
      activeAccountIndex: 0,
    });
  },
}));

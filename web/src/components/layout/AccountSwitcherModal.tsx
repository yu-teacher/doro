import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '../../store/authStore';
import { UserPlus, LogOut, Settings, X } from 'lucide-react';
import { authApi } from '../../api/authApi';

interface AccountSwitcherModalProps {
  isOpen: boolean;
  onClose: () => void;
  onOpenAddAccount: () => void;
}

export const AccountSwitcherModal: React.FC<AccountSwitcherModalProps> = ({
  isOpen,
  onClose,
  onOpenAddAccount,
}) => {
  const navigate = useNavigate();
  const { accounts, activeAccountIndex, switchAccount, logoutAll } = useAuthStore();
  const [switchingIndex, setSwitchingIndex] = useState<number | null>(null);

  if (!isOpen) return null;

  const activeAccount = accounts[activeAccountIndex] || accounts[0];

  const handleSwitch = (index: number) => {
    if (index === activeAccountIndex) return;
    setSwitchingIndex(index);
    switchAccount(index);
    onClose();
    setSwitchingIndex(null);
  };

  const handleLogoutAll = async () => {
    if (activeAccount?.sessionId) {
      try {
        await authApi.logout(activeAccount.sessionId);
      } catch {
        // ignore
      }
    }
    logoutAll();
    onClose();
    navigate('/login');
  };

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-end p-4 sm:p-6" onClick={onClose}>
      <div
        className="glass-card google-card-shadow mt-14 w-92 rounded-3xl p-5 shadow-2xl transition-all duration-200"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between pb-3 border-b border-slate-100">
          <span className="text-xs font-bold uppercase tracking-wider text-slate-400">Doro 계정 관리</span>
          <button onClick={onClose} className="text-slate-400 hover:text-slate-600 p-1 rounded-full hover:bg-slate-100">
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Active Account Card */}
        {activeAccount ? (
          <div className="mt-3 p-4 bg-indigo-50/70 border border-indigo-100 rounded-2xl flex flex-col items-center text-center">
            <div className="w-14 h-14 rounded-full overflow-hidden bg-gradient-to-tr from-indigo-600 to-violet-500 text-white flex items-center justify-center text-xl font-bold shadow-md shadow-indigo-500/20 mb-2">
              {activeAccount.profileImageUrl ? (
                <img src={activeAccount.profileImageUrl} alt={activeAccount.fullName} className="w-full h-full object-cover" />
              ) : (
                activeAccount.fullName ? activeAccount.fullName.charAt(0).toUpperCase() : 'U'
              )}
            </div>
            <h3 className="font-bold text-slate-800 text-base">{activeAccount.fullName || 'Doro User'}</h3>
            <p className="text-xs text-slate-500 font-medium">{activeAccount.email}</p>
            <div className="mt-1 flex items-center gap-1.5 px-2.5 py-0.5 bg-indigo-50 border border-indigo-100 rounded-full text-[11px] font-semibold text-indigo-700">
              <span className="w-1.5 h-1.5 rounded-full bg-indigo-600 animate-pulse"></span>
              현재 사용 중인 계정
            </div>

            <button
              onClick={() => {
                onClose();
                navigate('/account');
              }}
              className="mt-3 w-full py-2 px-4 rounded-xl border border-indigo-200 bg-white hover:bg-indigo-50 text-indigo-700 text-xs font-semibold flex items-center justify-center gap-1.5 transition-colors shadow-2xs cursor-pointer"
            >
              <Settings className="w-3.5 h-3.5" /> Doro 계정 센터 관리
            </button>
          </div>
        ) : (
          <div className="py-6 text-center text-slate-500 text-sm">로그인된 계정이 없습니다.</div>
        )}

        {/* Other Accounts List */}
        {accounts.length > 1 && (
          <div className="mt-4">
            <span className="text-[11px] font-bold text-slate-400 uppercase tracking-wider px-1">다른 계정</span>
            <div className="mt-2 space-y-1.5 max-h-44 overflow-y-auto pr-1">
              {accounts.map((acc, idx) => {
                if (idx === activeAccountIndex) return null;
                return (
                  <button
                    key={acc.email}
                    onClick={() => handleSwitch(idx)}
                    disabled={switchingIndex === idx}
                    className="w-full p-2.5 rounded-xl hover:bg-slate-50 border border-transparent hover:border-slate-200 flex items-center justify-between text-left transition-all group cursor-pointer"
                  >
                    <div className="flex items-center gap-3">
                      <div className="w-9 h-9 rounded-full overflow-hidden bg-slate-200 text-slate-700 flex items-center justify-center text-sm font-bold group-hover:bg-indigo-100 group-hover:text-indigo-700 transition-colors">
                        {acc.profileImageUrl ? (
                          <img src={acc.profileImageUrl} alt={acc.fullName} className="w-full h-full object-cover" />
                        ) : (
                          acc.fullName ? acc.fullName.charAt(0).toUpperCase() : 'U'
                        )}
                      </div>
                      <div>
                        <div className="text-xs font-bold text-slate-800 leading-tight">{acc.fullName}</div>
                        <div className="text-[11px] text-slate-400 leading-tight">{acc.email}</div>
                      </div>
                    </div>
                  </button>
                );
              })}
            </div>
          </div>
        )}

        {/* Action Buttons */}
        <div className="mt-4 pt-3 border-t border-slate-100 space-y-1.5">
          <button
            onClick={() => {
              onClose();
              onOpenAddAccount();
            }}
            className="w-full py-2.5 px-3 rounded-xl hover:bg-slate-100 text-slate-700 text-xs font-semibold flex items-center gap-2 transition-colors"
          >
            <UserPlus className="w-4 h-4 text-slate-500" />
            다른 계정 추가
          </button>

          <button
            onClick={handleLogoutAll}
            className="w-full py-2.5 px-3 rounded-xl hover:bg-red-50 text-red-600 text-xs font-semibold flex items-center gap-2 transition-colors"
          >
            <LogOut className="w-4 h-4 text-red-500" />
            모든 계정에서 로그아웃
          </button>
        </div>
      </div>
    </div>
  );
};

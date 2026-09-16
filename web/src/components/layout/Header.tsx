import React, { useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '../../store/authStore';
import { Shield, Grid, User, Terminal } from 'lucide-react';
import { AppLauncherModal } from './AppLauncherModal';
import { AccountSwitcherModal } from './AccountSwitcherModal';
import { AddAccountModal } from './AddAccountModal';

export const Header: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { accounts, activeAccountIndex } = useAuthStore();

  const [isAppLauncherOpen, setIsAppLauncherOpen] = useState(false);
  const [isAccountSwitcherOpen, setIsAccountSwitcherOpen] = useState(false);
  const [isAddAccountOpen, setIsAddAccountOpen] = useState(false);

  const activeAccount = accounts[activeAccountIndex] || accounts[0];
  const isAdmin = activeAccount?.role === 'ADMIN' || activeAccount?.role === 'SUPER_ADMIN';

  const isAuthPage = location.pathname === '/login' || location.pathname === '/signup';

  const handleLogoClick = () => {
    if (activeAccount) {
      navigate('/account', { state: { tab: 'home', timestamp: Date.now() } });
      window.scrollTo({ top: 0, behavior: 'smooth' });
    } else {
      navigate('/login');
    }
  };

  return (
    <>
      <header className="sticky top-0 z-40 w-full bg-white/90 backdrop-blur-md border-b border-slate-100">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 h-16 flex items-center justify-between">
          {/* Logo */}
          <div
            onClick={handleLogoClick}
            className="flex items-center gap-2.5 cursor-pointer group"
          >
            <div className="w-9 h-9 rounded-2xl bg-gradient-to-tr from-indigo-600 to-violet-500 text-white flex items-center justify-center shadow-md shadow-indigo-500/20 group-hover:scale-105 transition-transform">
              <Shield className="w-5 h-5" />
            </div>
            <div className="flex flex-col">
              <span className="text-base font-extrabold tracking-tight text-slate-800 leading-tight">Doro 계정</span>
              <span className="text-[10px] font-semibold text-slate-400 leading-tight">Central Identity Portal</span>
            </div>
          </div>

          {/* Right Actions */}
          <div className="flex items-center gap-2 sm:gap-3">
            {/* System Logs Navigation Button (Admins only) */}
            {isAdmin && (
              <button
                onClick={() => navigate('/logs')}
                title="시스템 로그 모니터링"
                className={`px-3 py-1.5 rounded-xl text-xs font-bold flex items-center gap-1.5 transition-colors cursor-pointer ${
                  location.pathname === '/logs'
                    ? 'bg-indigo-50 text-indigo-600 border border-indigo-200'
                    : 'text-slate-600 hover:text-slate-900 hover:bg-slate-100'
                }`}
              >
                <Terminal className="w-4 h-4" />
                <span className="hidden sm:inline">시스템 로그</span>
              </button>
            )}

            {/* App Launcher (9 Dots) */}
            <button
              onClick={() => {
                setIsAppLauncherOpen(!isAppLauncherOpen);
                setIsAccountSwitcherOpen(false);
              }}
              title="Doro 앱"
              className="p-2.5 text-slate-600 hover:text-slate-900 hover:bg-slate-100 rounded-full transition-colors cursor-pointer"
            >
              <Grid className="w-5 h-5" />
            </button>

            {/* Profile Avatar or Login Button */}
            {activeAccount ? (
              <button
                onClick={() => {
                  setIsAccountSwitcherOpen(!isAccountSwitcherOpen);
                  setIsAppLauncherOpen(false);
                }}
                className="relative flex items-center gap-2 p-1 rounded-full hover:ring-3 hover:ring-indigo-100 transition-all"
              >
                <div className="w-9 h-9 rounded-full overflow-hidden bg-gradient-to-tr from-indigo-600 to-violet-500 text-white flex items-center justify-center text-sm font-bold shadow-xs">
                  {activeAccount.profileImageUrl ? (
                    <img src={activeAccount.profileImageUrl} alt={activeAccount.fullName} className="w-full h-full object-cover" />
                  ) : (
                    activeAccount.fullName ? activeAccount.fullName.charAt(0).toUpperCase() : 'U'
                  )}
                </div>
              </button>
            ) : (
              !isAuthPage && (
                <button
                  onClick={() => navigate('/login')}
                  className="py-2 px-4 rounded-xl bg-indigo-600 hover:bg-indigo-700 text-white text-xs font-bold transition-all shadow-xs flex items-center gap-1.5"
                >
                  <User className="w-3.5 h-3.5" /> 로그인
                </button>
              )
            )}
          </div>
        </div>
      </header>

      {/* Modals */}
      <AppLauncherModal isOpen={isAppLauncherOpen} onClose={() => setIsAppLauncherOpen(false)} />
      <AccountSwitcherModal
        isOpen={isAccountSwitcherOpen}
        onClose={() => setIsAccountSwitcherOpen(false)}
        onOpenAddAccount={() => {
          setIsAccountSwitcherOpen(false);
          setIsAddAccountOpen(true);
        }}
      />
      <AddAccountModal isOpen={isAddAccountOpen} onClose={() => setIsAddAccountOpen(false)} />
    </>
  );
};

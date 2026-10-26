import React from 'react';
import { Link, useLocation } from 'react-router-dom';
import { LogIn } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { loginStateFrom } from '../utils/afterLogin';
import { buildServiceCards } from '../utils/serviceCatalog';
import { HubCard } from '../components/hub/HubCard';

export const HubPage: React.FC = () => {
  const location = useLocation();
  const { accounts, activeAccountIndex } = useAuthStore();
  const activeAccount = accounts[activeAccountIndex] || accounts[0];
  const isLoggedIn = Boolean(activeAccount);
  const isAdmin = activeAccount?.role === 'ADMIN' || activeAccount?.role === 'SUPER_ADMIN';
  const cards = buildServiceCards({ isLoggedIn, isAdmin });
  const services = cards.filter((c) => c.group === 'service' && c.audience === 'general');
  // 관리자에게만 나온다(일반 사용자의 허브에는 개발자 서비스가 없다)
  const devServices = cards.filter((c) => c.group === 'service' && c.audience === 'developer');
  const platform = cards.filter((c) => c.group === 'platform');

  return (
    <div className="max-w-5xl mx-auto px-4 sm:px-6 py-10 space-y-10">
      <section className="text-center space-y-4">
        <h1 className="text-3xl sm:text-4xl font-black tracking-tight text-slate-900">Doro</h1>
        <p className="text-slate-500 max-w-xl mx-auto">
          하나의 계정으로 모든 도로 서비스를 쓰세요. 로그인은 Doro 가 맡고, 각 서비스는 권한만 확인합니다.
        </p>
        {!isLoggedIn && (
          <div className="flex items-center justify-center gap-3 pt-2">
            <Link
              to="/login"
              state={loginStateFrom(location)}
              className="inline-flex items-center gap-2 px-5 py-2.5 rounded-xl bg-indigo-600 text-white text-sm font-bold hover:bg-indigo-700 transition-colors"
            >
              <LogIn className="w-4 h-4" /> 로그인
            </Link>
            <Link to="/signup" state={loginStateFrom(location)} className="px-5 py-2.5 rounded-xl border border-slate-200 text-sm font-bold text-slate-600 hover:bg-slate-50 transition-colors">
              가입하기
            </Link>
          </div>
        )}
      </section>

      <section aria-labelledby="hub-services">
        <h2 id="hub-services" className="text-xs font-bold uppercase tracking-wider text-slate-400 mb-4">
          서비스
        </h2>
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {services.map((card) => (
            <HubCard key={card.id} card={card} />
          ))}
        </div>
      </section>

      {devServices.length > 0 && (
        <section aria-labelledby="hub-dev">
          <h2 id="hub-dev" className="text-xs font-bold uppercase tracking-wider text-slate-400 mb-4">
            개발자 서비스 · 관리자에게만 보여요
          </h2>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {devServices.map((card) => (
              <HubCard key={card.id} card={card} />
            ))}
          </div>
        </section>
      )}

      <section aria-labelledby="hub-platform">
        <h2 id="hub-platform" className="text-xs font-bold uppercase tracking-wider text-slate-400 mb-4">
          Doro 플랫폼
        </h2>
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {platform.map((card) => (
            <HubCard key={card.id} card={card} />
          ))}
        </div>
      </section>
    </div>
  );
};

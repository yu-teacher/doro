import React from 'react';
import { Link, useLocation } from 'react-router-dom';
import { LogIn } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { loginStateFrom } from '../utils/afterLogin';
import { buildServiceCards } from '../utils/serviceCatalog';
import { HubCard } from '../components/hub/HubCard';

/** 개발자 제품(Doro Dev)의 입구. 개발자 서비스만 보이고, 일반 서비스(파티·게임·메뉴)는 이름도 나오지 않는다. */
export const DevHubPage: React.FC = () => {
  const location = useLocation();
  const { accounts, activeAccountIndex } = useAuthStore();
  const activeAccount = accounts[activeAccountIndex] || accounts[0];
  const isLoggedIn = Boolean(activeAccount);
  const isAdmin = activeAccount?.role === 'ADMIN' || activeAccount?.role === 'SUPER_ADMIN';
  const cards = buildServiceCards({ isLoggedIn, isAdmin, product: 'dev' });
  const services = cards.filter((c) => c.group === 'service');
  const admin = cards.filter((c) => c.group !== 'service');

  return (
    <div className="max-w-5xl mx-auto px-4 sm:px-6 py-10 space-y-10">
      <section className="text-center space-y-4">
        <h1 className="text-3xl sm:text-4xl font-black tracking-tight text-slate-900">Doro Dev</h1>
        <p className="text-slate-500 max-w-xl mx-auto">개발 이야기를 읽고 쓰고, 면접을 준비하는 개발자를 위한 공간이에요.</p>
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

      <section aria-labelledby="dev-services">
        <h2 id="dev-services" className="text-xs font-bold uppercase tracking-wider text-slate-400 mb-4">
          서비스
        </h2>
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {services.map((card) => (
            <HubCard key={card.id} card={card} />
          ))}
        </div>
      </section>

      {admin.length > 0 && (
        <section aria-labelledby="dev-admin">
          <h2 id="dev-admin" className="text-xs font-bold uppercase tracking-wider text-slate-400 mb-4">
            관리자
          </h2>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {admin.map((card) => (
              <HubCard key={card.id} card={card} />
            ))}
          </div>
        </section>
      )}
    </div>
  );
};

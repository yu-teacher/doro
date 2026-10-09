import React from 'react';
import { Link } from 'react-router-dom';
import { BookOpen, Gamepad2, MapPin, Shield, Terminal, User, Utensils, ExternalLink, ArrowRight, FileText, LogIn } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { buildServiceCards, type ServiceCard, type ServiceIcon } from '../utils/serviceCatalog';

const ICONS: Record<ServiceIcon, { Icon: React.ComponentType<{ className?: string }>; color: string }> = {
  blog: { Icon: BookOpen, color: 'text-emerald-500 bg-emerald-50' },
  party: { Icon: MapPin, color: 'text-sky-500 bg-sky-50' },
  games: { Icon: Gamepad2, color: 'text-amber-500 bg-amber-50' },
  menu: { Icon: Utensils, color: 'text-pink-500 bg-pink-50' },
  account: { Icon: User, color: 'text-indigo-500 bg-indigo-50' },
  iam: { Icon: FileText, color: 'text-indigo-500 bg-indigo-50' },
  guard: { Icon: Shield, color: 'text-indigo-500 bg-indigo-50' },
  logs: { Icon: Terminal, color: 'text-slate-600 bg-slate-100' },
};

const CardBody: React.FC<{ card: ServiceCard }> = ({ card }) => {
  const { Icon, color } = ICONS[card.icon];
  return (
    <>
      <div className={`w-12 h-12 rounded-2xl flex items-center justify-center ${color}`}>
        <Icon className="w-6 h-6" />
      </div>
      <div className="mt-4 flex items-center justify-between gap-2">
        <h3 className="text-base font-extrabold text-slate-800">{card.name}</h3>
        {card.internal ? (
          <ArrowRight className="w-4 h-4 text-slate-300 group-hover:text-indigo-500 transition-colors" />
        ) : (
          <ExternalLink className="w-4 h-4 text-slate-300 group-hover:text-indigo-500 transition-colors" />
        )}
      </div>
      <p className="mt-1 text-sm text-slate-500 leading-relaxed">{card.desc}</p>
    </>
  );
};

const CARD_CLASS =
  'group glass-card google-card-shadow rounded-3xl p-6 border border-slate-100 hover:border-indigo-200 hover:-translate-y-0.5 transition-all duration-200 block';

const HubCard: React.FC<{ card: ServiceCard }> = ({ card }) =>
  card.internal ? (
    <Link to={card.href} className={CARD_CLASS}>
      <CardBody card={card} />
    </Link>
  ) : (
    // 다른 서비스(블로그·파티 등)는 별도 앱이라 라우터가 아니라 일반 링크로 전체 페이지를 이동한다.
    <a href={card.href} className={CARD_CLASS}>
      <CardBody card={card} />
    </a>
  );

export const HubPage: React.FC = () => {
  const { accounts, activeAccountIndex } = useAuthStore();
  const activeAccount = accounts[activeAccountIndex] || accounts[0];
  const isLoggedIn = Boolean(activeAccount);
  const isAdmin = activeAccount?.role === 'ADMIN' || activeAccount?.role === 'SUPER_ADMIN';
  const cards = buildServiceCards({ isLoggedIn, isAdmin });
  const services = cards.filter((c) => c.group === 'service');
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
              className="inline-flex items-center gap-2 px-5 py-2.5 rounded-xl bg-indigo-600 text-white text-sm font-bold hover:bg-indigo-700 transition-colors"
            >
              <LogIn className="w-4 h-4" /> 로그인
            </Link>
            <Link to="/signup" className="px-5 py-2.5 rounded-xl border border-slate-200 text-sm font-bold text-slate-600 hover:bg-slate-50 transition-colors">
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

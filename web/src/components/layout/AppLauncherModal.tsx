import React from 'react';
import { Link } from 'react-router-dom';
import { useAuthStore } from '../../store/authStore';
import { BookOpen, Shield, Terminal, ExternalLink, X, Utensils, Home, MapPin, Gamepad2, GraduationCap } from 'lucide-react';
import { getIamDocsUrl } from '../../utils/urlUtils';
import { buildLauncherApps, type LauncherIcon } from '../../utils/serviceCatalog';
import type { Product } from '../../utils/product';
import { trackEvent } from '../../utils/analytics';

interface AppLauncherModalProps {
  isOpen: boolean;
  onClose: () => void;
  /** 들어온 입구. 개발자 입구에서는 개발자 서비스만 나열한다. */
  product?: Product;
}

const ICONS: Record<LauncherIcon, { Icon: React.ComponentType<{ className?: string }>; color: string }> = {
  home: { Icon: Home, color: 'text-indigo-500 bg-indigo-50' },
  blog: { Icon: BookOpen, color: 'text-emerald-500 bg-emerald-50' },
  party: { Icon: MapPin, color: 'text-sky-500 bg-sky-50' },
  games: { Icon: Gamepad2, color: 'text-amber-500 bg-amber-50' },
  interview: { Icon: GraduationCap, color: 'text-violet-500 bg-violet-50' },
  menu: { Icon: Utensils, color: 'text-pink-500 bg-pink-50' },
  account: { Icon: Shield, color: 'text-indigo-500 bg-indigo-50' },
  iam: { Icon: Shield, color: 'text-indigo-500 bg-indigo-50' },
  guard: { Icon: Shield, color: 'text-indigo-500 bg-indigo-50' },
  logs: { Icon: Terminal, color: 'text-indigo-500 bg-indigo-50' },
};

export const AppLauncherModal: React.FC<AppLauncherModalProps> = ({ isOpen, onClose, product = 'hub' }) => {
  const { accounts, activeAccountIndex } = useAuthStore();
  const activeAccount = accounts[activeAccountIndex] || accounts[0];
  const isAdmin = activeAccount?.role === 'ADMIN' || activeAccount?.role === 'SUPER_ADMIN';

  if (!isOpen) return null;

  const iamDocsUrl = getIamDocsUrl();
  const apps = buildLauncherApps({ isAdmin, product });

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-end p-4 sm:p-6" onClick={onClose}>
      <div
        className="glass-card google-card-shadow mt-14 w-84 rounded-3xl p-5 shadow-2xl transition-all duration-200"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between pb-3 border-b border-slate-100">
          <span className="text-xs font-bold uppercase tracking-wider text-slate-400">{product === 'dev' ? 'Doro Dev Apps' : 'Doro Apps'}</span>
          <button onClick={onClose} className="text-slate-400 hover:text-slate-600 p-1 rounded-full hover:bg-slate-100 cursor-pointer">
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className={`grid ${apps.length === 1 ? 'grid-cols-1' : apps.length <= 4 ? 'grid-cols-2' : 'grid-cols-3'} gap-3 pt-4`}>
          {apps.map((app) => {
            const { Icon, color } = ICONS[app.icon];
            const track = () => {
              trackEvent('service_launch', { service_name: app.name, destination: app.href });
              if (app.internal) onClose();
            };
            const body = (
              <>
                <div className={`w-12 h-12 rounded-2xl flex items-center justify-center ${color} mb-2 shadow-xs group-hover:shadow-md transition-shadow`}>
                  <Icon className="w-6 h-6" />
                </div>
                <span className="text-xs font-semibold text-slate-700 leading-tight group-hover:text-indigo-600">{app.name}</span>
                <span className="text-[10px] text-slate-400 mt-0.5 line-clamp-1">{app.desc}</span>
              </>
            );
            const cls = 'group flex flex-col items-center justify-center p-3 rounded-2xl hover:bg-slate-50 transition-all hover:scale-105 text-center';
            // 포털 안의 화면은 라우터로(새로고침 없이), 다른 서비스·외부 문서는 전체 페이지 이동
            return app.internal ? (
              <Link key={app.id} to={app.href} onClick={track} className={cls}>
                {body}
              </Link>
            ) : (
              <a key={app.id} href={app.href} target={app.href.startsWith('http') ? '_blank' : '_self'} rel="noreferrer" onClick={track} className={cls}>
                {body}
              </a>
            );
          })}
        </div>

        {isAdmin && iamDocsUrl && (
          <div className="mt-4 pt-3 border-t border-slate-100 text-center">
            <a
              href={iamDocsUrl}
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center text-[11px] font-semibold text-indigo-600 hover:text-indigo-700 gap-1"
            >
              [관리자 전용] IAM OpenAPI 명세 <ExternalLink className="w-3 h-3" />
            </a>
          </div>
        )}
      </div>
    </div>
  );
};

import React from 'react';
import { useAuthStore } from '../../store/authStore';
import { BookOpen, Shield, Terminal, ExternalLink, X, Utensils } from 'lucide-react';
import { getBlogUrl, getGuardDocsUrl, getIamDocsUrl, getMenuUrl } from '../../utils/urlUtils';
import { trackEvent } from '../../utils/analytics';

interface AppLauncherModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export const AppLauncherModal: React.FC<AppLauncherModalProps> = ({ isOpen, onClose }) => {
  const { accounts, activeAccountIndex } = useAuthStore();
  const activeAccount = accounts[activeAccountIndex] || accounts[0];
  const isAdmin = activeAccount?.role === 'ADMIN' || activeAccount?.role === 'SUPER_ADMIN';

  if (!isOpen) return null;

  const baseApps = [
    {
      name: 'DORO.log',
      desc: '개발자 오픈 기술 블로그',
      icon: BookOpen,
      color: 'text-emerald-500 bg-emerald-50',
      link: getBlogUrl(),
    },
    {
      name: '도로메뉴',
      desc: '메뉴 정해주는 도로롱',
      icon: Utensils,
      color: 'text-pink-500 bg-pink-50',
      link: getMenuUrl(),
    },
  ];

  // 문서 URL 은 환경변수(VITE_GUARD_DOCS_URL / VITE_IAM_DOCS_URL)로만 주입하며, 미설정이면 항목을 숨긴다.
  const guardDocsUrl = getGuardDocsUrl();
  const iamDocsUrl = getIamDocsUrl();

  const adminApps = [
    ...(guardDocsUrl
      ? [{ name: 'Doro Guard', desc: 'Zanzibar ReBAC API', icon: Shield, color: 'text-indigo-500 bg-indigo-50', link: guardDocsUrl }]
      : []),
    { name: 'Doro Ops Logs', desc: '시스템 관제 로그', icon: Terminal, color: 'text-indigo-500 bg-indigo-50', link: '/logs' },
  ];

  const apps = isAdmin ? [...baseApps, ...adminApps] : baseApps;

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-end p-4 sm:p-6" onClick={onClose}>
      <div
        className="glass-card google-card-shadow mt-14 w-84 rounded-3xl p-5 shadow-2xl transition-all duration-200"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between pb-3 border-b border-slate-100">
          <span className="text-xs font-bold uppercase tracking-wider text-slate-400">Doro Apps</span>
          <button onClick={onClose} className="text-slate-400 hover:text-slate-600 p-1 rounded-full hover:bg-slate-100 cursor-pointer">
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className={`grid ${apps.length === 1 ? 'grid-cols-1' : apps.length <= 4 ? 'grid-cols-2' : 'grid-cols-3'} gap-3 pt-4`}>
          {apps.map((app) => {
            const Icon = app.icon;
            return (
              <a
                key={app.name}
                href={app.link}
                target={app.link.startsWith('http') ? '_blank' : '_self'}
                rel="noreferrer"
                onClick={() => {
                  trackEvent('service_launch', {
                    service_name: app.name,
                    destination: app.link,
                  });
                }}
                className="group flex flex-col items-center justify-center p-3 rounded-2xl hover:bg-slate-50 transition-all hover:scale-105 text-center"
              >
                <div className={`w-12 h-12 rounded-2xl flex items-center justify-center ${app.color} mb-2 shadow-xs group-hover:shadow-md transition-shadow`}>
                  <Icon className="w-6 h-6" />
                </div>
                <span className="text-xs font-semibold text-slate-700 leading-tight group-hover:text-indigo-600">{app.name}</span>
                <span className="text-[10px] text-slate-400 mt-0.5 line-clamp-1">{app.desc}</span>
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

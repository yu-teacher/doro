import React from 'react';
import { useAuthStore } from '../../store/authStore';
import { FileText, HardDrive, Presentation, LayoutGrid, Shield, ExternalLink, X } from 'lucide-react';

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
    { name: 'Doro Docs', desc: '실시간 문서 협업', icon: FileText, color: 'text-blue-500 bg-blue-50', link: '#' },
    { name: 'Doro Drive', desc: '클라우드 파일 저장소', icon: HardDrive, color: 'text-amber-500 bg-amber-50', link: '#' },
    { name: 'Doro Slides', desc: '프레젠테이션 제작', icon: Presentation, color: 'text-orange-500 bg-orange-50', link: '#' },
    { name: 'Doro Board', desc: '사내 팀 게시판', icon: LayoutGrid, color: 'text-emerald-500 bg-emerald-50', link: '#' },
  ];

  const adminApps = [
    { name: 'Doro Guard', desc: 'Zanzibar ReBAC API', icon: Shield, color: 'text-indigo-500 bg-indigo-50', link: 'http://localhost:28081/swagger-ui.html' },
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

        <div className={`grid ${apps.length <= 4 ? 'grid-cols-2' : 'grid-cols-3'} gap-3 pt-4`}>
          {apps.map((app) => {
            const Icon = app.icon;
            return (
              <a
                key={app.name}
                href={app.link}
                target={app.link.startsWith('http') ? '_blank' : '_self'}
                rel="noreferrer"
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

        {isAdmin && (
          <div className="mt-4 pt-3 border-t border-slate-100 text-center">
            <a
              href="http://localhost:28080/swagger-ui.html"
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

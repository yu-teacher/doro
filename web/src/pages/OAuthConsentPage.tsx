import React, { useState } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../store/authStore';
import { Shield, Check, CheckCircle2 } from 'lucide-react';

export const OAuthConsentPage: React.FC = () => {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const { getActiveAccount } = useAuthStore();
  const activeAccount = getActiveAccount();

  const clientId = searchParams.get('client_id') || 'doro-docs-app';
  const redirectUri = searchParams.get('redirect_uri') || 'https://docs.doro.local/callback';

  const [approved, setApproved] = useState(false);

  const handleApprove = () => {
    setApproved(true);
    setTimeout(() => {
      // 실제 OAuth 2.1 인가 코드 리다이렉트 시뮬레이션
      window.location.href = `${redirectUri}?code=doro_auth_code_sample_123&state=state123`;
    }, 1500);
  };

  const handleDeny = () => {
    navigate('/account');
  };

  return (
    <div className="min-h-[calc(100vh-4rem)] flex items-center justify-center p-4">
      <div className="w-full max-w-md">
        <div className="glass-card google-card-shadow rounded-3xl p-8 sm:p-10 text-center">
          <div className="inline-flex w-14 h-14 rounded-2xl bg-gradient-to-tr from-indigo-600 to-violet-500 text-white items-center justify-center shadow-lg shadow-indigo-500/25 mb-4">
            <Shield className="w-7 h-7" />
          </div>

          <h1 className="text-xl font-extrabold text-slate-900">Doro 계정으로 로그인</h1>
          <p className="text-xs text-slate-500 mt-1">
            <strong className="text-indigo-600 font-bold">{clientId}</strong> 앱에서 다음 권한을 요청합니다.
          </p>

          {activeAccount && (
            <div className="mt-4 p-3 bg-slate-50 border border-slate-200 rounded-2xl flex items-center justify-center gap-3">
              <div className="w-8 h-8 rounded-full bg-indigo-600 text-white flex items-center justify-center text-xs font-bold">
                {activeAccount.fullName.charAt(0)}
              </div>
              <div className="text-left">
                <div className="text-xs font-bold text-slate-800">{activeAccount.fullName}</div>
                <div className="text-[11px] text-slate-500">{activeAccount.email}</div>
              </div>
            </div>
          )}

          {/* Scopes */}
          <div className="mt-6 p-4 bg-indigo-50/70 border border-indigo-100 rounded-2xl text-left space-y-2.5">
            <span className="text-[11px] font-bold text-indigo-900 uppercase tracking-wider block">요청된 접근 권한:</span>
            <div className="flex items-center gap-2 text-xs text-slate-700">
              <Check className="w-4 h-4 text-emerald-600" /> 기본 프로필 정보 (이름, 사용자 ID)
            </div>
            <div className="flex items-center gap-2 text-xs text-slate-700">
              <Check className="w-4 h-4 text-emerald-600" /> 이메일 주소 확인
            </div>
            <div className="flex items-center gap-2 text-xs text-slate-700">
              <Check className="w-4 h-4 text-emerald-600" /> Doro Guard ReBAC 권한 상태 동기화
            </div>
          </div>

          {approved ? (
            <div className="mt-6 p-4 bg-emerald-50 border border-emerald-200 rounded-2xl text-xs font-bold text-emerald-700 flex items-center justify-center gap-2 animate-in fade-in">
              <CheckCircle2 className="w-4 h-4 text-emerald-600" /> 승인 완료! 서비스로 이동 중입니다...
            </div>
          ) : (
            <div className="mt-6 pt-4 border-t border-slate-100 flex gap-3">
              <button
                onClick={handleDeny}
                className="flex-1 py-3 px-4 border border-slate-200 hover:bg-slate-50 rounded-xl text-xs font-bold text-slate-600 transition-colors"
              >
                취소
              </button>
              <button
                onClick={handleApprove}
                className="flex-1 py-3 px-4 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl text-xs font-bold transition-all shadow-md shadow-indigo-500/25"
              >
                계속 (승인)
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

import React, { useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams, useNavigate } from 'react-router-dom';
import { isAxiosError } from 'axios';
import { useAuthStore } from '../store/authStore';
import { apiClient } from '../api/client';
import { buildAuthorizationRedirect, parseConsentRequest } from '../utils/oauthConsent';
import { saveConsentReturn } from '../utils/consentReturn';
import { Shield, Check, CheckCircle2, AlertTriangle } from 'lucide-react';

interface AuthorizeResponse {
  data?: { code?: string; state?: string };
}

export const OAuthConsentPage: React.FC = () => {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const { getActiveAccount } = useAuthStore();
  const activeAccount = getActiveAccount();

  const parsed = useMemo(() => parseConsentRequest(searchParams), [searchParams]);

  const [approved, setApproved] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  // 로그인 전이라면 검증된 동의 요청을 보관해 두었다가, 로그인 직후 이 화면으로 돌아오게 한다.
  const needsLogin = parsed.ok && !activeAccount;
  useEffect(() => {
    if (needsLogin) {
      saveConsentReturn(searchParams);
    }
  }, [needsLogin, searchParams]);

  const handleApprove = async () => {
    if (!parsed.ok || submitting) return;
    const { clientId, redirectUri, codeChallenge, state } = parsed.request;
    setSubmitting(true);
    setErrorMessage(null);
    try {
      // 실제 인가 서버를 호출한다. redirect_uri 허용 여부는 서버가 판정하며, 허용되지 않으면 코드를 발급하지 않는다.
      const response = await apiClient.get<AuthorizeResponse>('/oauth2/authorize', {
        params: {
          client_id: clientId,
          redirect_uri: redirectUri,
          response_type: 'code',
          code_challenge: codeChallenge,
          ...(state ? { state } : {}),
        },
      });
      const code = response.data?.data?.code;
      if (!code) {
        setErrorMessage('인가 코드를 받지 못했습니다. 잠시 후 다시 시도해 주세요.');
        return;
      }
      setApproved(true);
      window.location.assign(buildAuthorizationRedirect(redirectUri, code, state));
    } catch (error) {
      const rejected = isAxiosError(error) && error.response?.status === 400;
      setErrorMessage(rejected ? '허용되지 않은 요청입니다. 앱 관리자에게 문의해 주세요.' : '승인 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.');
    } finally {
      setSubmitting(false);
    }
  };

  const handleDeny = () => {
    navigate('/account');
  };

  if (!parsed.ok || !activeAccount) {
    return (
      <div className="min-h-[calc(100vh-4rem)] flex items-center justify-center p-4">
        <div className="w-full max-w-md glass-card google-card-shadow rounded-3xl p-8 text-center">
          <div className="inline-flex w-12 h-12 rounded-2xl bg-amber-100 text-amber-600 items-center justify-center mb-4">
            <AlertTriangle className="w-6 h-6" />
          </div>
          <h1 className="text-lg font-extrabold text-slate-900">
            {parsed.ok ? '로그인이 필요합니다' : '잘못된 요청입니다'}
          </h1>
          <p className="text-xs text-slate-500 mt-2">
            {parsed.ok ? 'Doro 계정으로 로그인한 뒤 다시 시도해 주세요.' : parsed.reason}
          </p>
          <Link to={parsed.ok ? '/login' : '/account'} className="inline-block mt-6 py-2.5 px-5 bg-indigo-600 text-white rounded-xl text-xs font-bold">
            {parsed.ok ? '로그인으로 이동' : '내 계정으로 돌아가기'}
          </Link>
        </div>
      </div>
    );
  }

  const { clientId, redirectHost } = parsed.request;

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
          <p className="text-[11px] text-slate-400 mt-1">승인 후 <strong className="font-semibold text-slate-600">{redirectHost}</strong> 로 이동합니다.</p>

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

          {errorMessage && (
            <div role="alert" className="mt-4 p-3 bg-rose-50 border border-rose-200 rounded-xl text-xs font-medium text-rose-700">
              {errorMessage}
            </div>
          )}

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
                disabled={submitting}
                className="flex-1 disabled:opacity-60 py-3 px-4 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl text-xs font-bold transition-all shadow-md shadow-indigo-500/25"
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

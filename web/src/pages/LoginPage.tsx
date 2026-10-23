import React, { useState, useEffect, useRef } from 'react';
import { useNavigate, useLocation, Link } from 'react-router-dom';
import { authApi } from '../api/authApi';
import { MAX_ACCOUNTS } from '../api/cookieSession';
import { useAuthStore } from '../store/authStore';
import {
  Shield,
  Lock,
  Mail,
  AlertCircle,
  Loader2,
  KeyRound,
  ArrowRight,
  ArrowLeft,
  ChevronDown,
  Eye,
  EyeOff,
} from 'lucide-react';
import { getErrorMessage } from '../utils/errorUtils';
import { parseJwtPayload } from '../utils/jwtUtils';
import { clearConsentReturn } from '../utils/consentReturn';
import { resolveAfterLoginPath } from '../utils/afterLogin';
import { consumeLoginNotice } from '../utils/loginNotice';
import { parseSocialReturn, socialErrorMessage, socialStartUrl, type SocialProvider } from '../utils/socialLogin';

/** 다른 화면(예: 전체 로그아웃)이 로그인 화면에 넘기는 안내 문구 */
function readNotice(state: unknown): string | null {
  if (typeof state !== 'object' || state === null) return null;
  const notice = (state as Record<string, unknown>).notice;
  return typeof notice === 'string' && notice ? notice : null;
}

type LoginStep = 'email' | 'password' | '2fa';

const SOCIAL_ICONS: Record<string, React.ReactNode> = {
  github: (
    <svg viewBox="0 0 16 16" className="w-5 h-5" aria-hidden="true" fill="currentColor">
      <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z" />
    </svg>
  ),
  google: (
    <svg viewBox="0 0 24 24" className="w-5 h-5" aria-hidden="true">
      <path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z" />
      <path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" />
      <path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z" />
      <path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" />
    </svg>
  ),
};

export const LoginPage: React.FC = () => {
  const navigate = useNavigate();
  const { accounts, activeAccountIndex, addAccount } = useAuthStore();
  const activeAccount = accounts[activeAccountIndex] || accounts[0];
  const location = useLocation();
  // 안내는 한 번만 보여 준다: 라우터 상태(전체 로그아웃·탈퇴 예약) 또는 세션이 끝나 다시 불러온 경우(sessionStorage)
  const [notice] = useState<string | null>(() => readNotice(location.state) ?? consumeLoginNotice());
  // 로그인 뒤 갈 곳: 동의 요청(서비스 로그인) > 로그인 전에 보던 화면 > 내 계정 (앱 내부 경로만, 마운트 시점 1회 확정)
  const [afterLoginPath] = useState<string>(() => resolveAfterLoginPath(location.state));

  useEffect(() => {
    if (activeAccount) {
      clearConsentReturn();
      navigate(afterLoginPath, { replace: true });
    }
  }, [activeAccount, afterLoginPath, navigate]);

  // Step state
  const [step, setStep] = useState<LoginStep>('email');

  // Form states
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [loading, setLoading] = useState(false);
  const [emailLoading, setEmailLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [accountInfo, setAccountInfo] = useState<{ email: string; name: string; profileImageUrl?: string | null } | null>(null);

  // 소셜 로그인: 켜져 있는 공급자 목록, 시작 중 표시, 2FA 로 이어질 때 쿠키를 둘 슬롯
  const [socialProviders, setSocialProviders] = useState<SocialProvider[]>([]);
  const [socialLoading, setSocialLoading] = useState(false);
  const [socialSlot, setSocialSlot] = useState<number | null>(null);
  const handledSocialReturn = useRef(false);

  // 2FA states
  const [tempTicket, setTempTicket] = useState('');
  const [totpCode, setTotpCode] = useState('');
  const [twoFaLoading, setTwoFaLoading] = useState(false);

  useEffect(() => {
    let cancelled = false;
    authApi.socialProviders()
      .then((list) => { if (!cancelled && Array.isArray(list)) setSocialProviders(list); })
      .catch(() => { /* 소셜 로그인을 쓸 수 없으면 버튼만 보이지 않는다 */ });
    return () => { cancelled = true; };
  }, []);

  // 공급자 화면에서 돌아온 경우(/login?social=ok|2fa|error): 서버가 심은 슬롯 쿠키로 액세스 토큰을 받아 로그인을 마친다.
  useEffect(() => {
    if (handledSocialReturn.current) return;
    const result = parseSocialReturn(new URLSearchParams(location.search));
    if (!result) return;
    handledSocialReturn.current = true;
    // 티켓 등이 주소창·기록에 남지 않도록 쿼리를 바로 지운다.
    navigate('/login', { replace: true, state: location.state });

    if (result.kind === 'error') {
      setError(socialErrorMessage(result.code));
      return;
    }
    if (result.kind === '2fa') {
      setSocialSlot(result.slot);
      setTempTicket(result.ticket);
      setStep('2fa');
      return;
    }
    setLoading(true);
    authApi.refreshFromCookie(result.slot)
      .then(async (tokens) => {
        const decoded = parseJwtPayload(tokens.accessToken);
        if (!decoded?.email) {
          setError(socialErrorMessage('provider_error'));
          return;
        }
        addAccount({
          userId: decoded.sub,
          email: decoded.email,
          fullName: decoded.email.split('@')[0],
          profileImageUrl: null,
          role: decoded.role || 'USER',
          accessToken: tokens.accessToken,
          slot: result.slot,
          sessionId: tokens.sessionId,
          userIndex: tokens.userIndex ?? 0,
        });
        // 이름·사진은 프로필에서 채운다(이동은 이미 시작됐을 수 있어 실패해도 로그인에는 영향이 없다).
        try {
          const profile = await authApi.getProfile();
          useAuthStore.getState().updateProfileByEmail(decoded.email, {
            fullName: profile.name,
            profileImageUrl: profile.profileImageUrl ?? null,
            role: profile.role,
          });
        } catch {
          // 다음 화면이 프로필을 다시 불러온다
        }
      })
      .catch((err: unknown) => setError(getErrorMessage(err, socialErrorMessage('provider_error'))))
      .finally(() => setLoading(false));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleSocialStart = (provider: SocialProvider) => {
    setError(null);
    // 돌아왔을 때 리프레시 쿠키를 둘 빈 슬롯. 같은 계정으로 다시 들어오면 그 계정의 슬롯이 새 쿠키로 덮인다.
    const slot = useAuthStore.getState().slotFor('');
    if (slot === null) {
      setError('이 브라우저에 로그인할 수 있는 계정은 최대 ' + MAX_ACCOUNTS + '개입니다. 다른 계정을 로그아웃한 뒤 다시 시도해 주세요.');
      return;
    }
    setSocialLoading(true);
    window.location.assign(socialStartUrl(provider.id, slot));
  };

  // 1단계: 이메일 실제 계정 사전 조회 (Google Account Lookup)
  const handleEmailNext = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);

    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    if (!email || !emailRegex.test(email.trim())) {
      setError('올바른 이메일 주소를 입력해 주세요.');
      return;
    }

    setEmailLoading(true);
    try {
      const res = await authApi.lookupAccount(email.trim());
      setAccountInfo(res);
      setStep('password');
    } catch (err: unknown) {
      setError(getErrorMessage(err, 'Doro 계정을 찾을 수 없습니다.'));
    } finally {
      setEmailLoading(false);
    }
  };

  // 2단계: 비밀번호 제출 및 로그인
  const handlePasswordSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError(null);

    try {
      const slot = useAuthStore.getState().slotFor(email.trim());
      if (slot === null) {
        setError('이 브라우저에 로그인할 수 있는 계정은 최대 ' + MAX_ACCOUNTS + '개입니다. 다른 계정을 로그아웃한 뒤 다시 시도해 주세요.');
        return;
      }
      const res = await authApi.login({ email: email.trim(), password }, slot);

      if (res.tokens && res.tokens.accessToken) {
        const decoded = parseJwtPayload(res.tokens.accessToken);
        addAccount({
          userId: decoded?.sub || '',
          email: email.trim(),
          fullName: accountInfo?.name || email.trim().split('@')[0],
          profileImageUrl: accountInfo?.profileImageUrl || null,
          role: decoded?.role || 'USER',
          accessToken: res.tokens.accessToken,
          slot,
          sessionId: res.tokens.sessionId,
          userIndex: res.tokens.userIndex ?? 0,
        });
        // 로그인 성공 후 이동은 activeAccount 변화를 감지하는 effect 가 담당한다.
      } else if (res.requires2fa && res.tempTicket) {
        setTempTicket(res.tempTicket);
        setStep('2fa');
      } else {
        setError('로그인 응답을 처리할 수 없습니다.');
      }
    } catch (err: unknown) {
      setError(getErrorMessage(err, '비밀번호가 올바르지 않습니다. 다시 확인해 주세요.'));
    } finally {
      setLoading(false);
    }
  };

  // 3단계: 2FA TOTP 코드 제출
  const handle2faSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setTwoFaLoading(true);
    setError(null);

    try {
      const slot = socialSlot ?? useAuthStore.getState().slotFor(email.trim());
      if (slot === null) {
        setError('이 브라우저에 로그인할 수 있는 계정은 최대 ' + MAX_ACCOUNTS + '개입니다. 다른 계정을 로그아웃한 뒤 다시 시도해 주세요.');
        return;
      }
      const res = await authApi.loginWith2fa({ tempTicket, code: totpCode }, slot);

      if (res && res.accessToken) {
        const decoded = parseJwtPayload(res.accessToken);
        // 소셜 로그인에서 이어진 2FA 는 이메일을 입력하지 않았으므로 토큰의 이메일을 쓴다.
        const accountEmail = socialSlot !== null ? decoded?.email || '' : email.trim();
        addAccount({
          userId: decoded?.sub || '',
          email: accountEmail,
          fullName: accountInfo?.name || accountEmail.split('@')[0],
          profileImageUrl: accountInfo?.profileImageUrl || null,
          role: decoded?.role || 'USER',
          accessToken: res.accessToken,
          slot,
          sessionId: res.sessionId,
          userIndex: res.userIndex ?? 0,
        });
        // 로그인 성공 후 이동은 activeAccount 변화를 감지하는 effect 가 담당한다.
      } else {
        setError('2FA 인증에 실패했습니다.');
      }
    } catch (err: unknown) {
      setError(getErrorMessage(err, '인증 코드가 올바르지 않거나 만료되었습니다.'));
    } finally {
      setTwoFaLoading(false);
    }
  };

  // 이전 단계(이메일 입력)로 되돌아가기
  const handleBackToEmail = () => {
    setPassword('');
    setError(null);
    setStep('email');
  };

  return (
    <div className="min-h-[calc(100vh-4rem)] flex items-center justify-center p-4">
      <div className="w-full max-w-md">
        <div className="glass-card google-card-shadow rounded-3xl p-8 sm:p-10 transition-all border border-slate-100">
          {/* Header */}
          <div className="text-center mb-8">
            <div className="inline-flex w-14 h-14 rounded-2xl bg-gradient-to-tr from-indigo-600 to-violet-500 text-white items-center justify-center shadow-lg shadow-indigo-500/25 mb-4">
              <Shield className="w-7 h-7" />
            </div>

            {step === 'email' && (
              <>
                <h1 className="text-2xl font-extrabold text-slate-900 tracking-tight">로그인</h1>
                <p className="text-sm text-slate-500 mt-1.5 font-medium">Doro 계정 사용</p>
              </>
            )}

            {step === 'password' && (
              <>
                <h1 className="text-2xl font-extrabold text-slate-900 tracking-tight">
                  {accountInfo?.name ? `${accountInfo.name}님, 환영합니다` : '환영합니다'}
                </h1>
                {/* Google Style Account Chip */}
                <div className="mt-3 inline-flex max-w-full items-center">
                  <button
                    type="button"
                    onClick={handleBackToEmail}
                    className="inline-flex max-w-full items-center gap-2 px-3.5 py-1.5 rounded-full border border-slate-200 bg-slate-50/80 hover:bg-slate-100/80 text-xs font-semibold text-slate-700 transition-colors group cursor-pointer shadow-2xs"
                    title="다른 계정으로 로그인"
                  >
                    {accountInfo?.profileImageUrl ? (
                      <img src={accountInfo.profileImageUrl} alt="Profile" className="w-5 h-5 shrink-0 rounded-full object-cover" />
                    ) : (
                      <div className="w-5 h-5 shrink-0 rounded-full bg-gradient-to-tr from-indigo-600 to-violet-500 text-white flex items-center justify-center text-[10px] font-bold">
                        {accountInfo?.name ? accountInfo.name.charAt(0).toUpperCase() : 'U'}
                      </div>
                    )}
                    <span className="min-w-0 break-all text-left">{email}</span>
                    <ChevronDown className="w-3.5 h-3.5 shrink-0 text-slate-400 group-hover:text-slate-600 transition-transform" />
                  </button>
                </div>
              </>
            )}

            {step === '2fa' && (
              <>
                <h1 className="text-2xl font-extrabold text-slate-900 tracking-tight">2단계 인증</h1>
                {/* Account Chip */}
                <div className="mt-3 inline-flex max-w-full items-center">
                  <div className="inline-flex max-w-full items-center gap-2 px-3.5 py-1.5 rounded-full border border-indigo-100 bg-indigo-50/50 text-xs font-semibold text-indigo-900">
                    {accountInfo?.profileImageUrl ? (
                      <img src={accountInfo.profileImageUrl} alt="Profile" className="w-5 h-5 shrink-0 rounded-full object-cover" />
                    ) : (
                      <div className="w-5 h-5 shrink-0 rounded-full bg-indigo-600 text-white flex items-center justify-center text-[10px] font-bold">
                        {accountInfo?.name ? accountInfo.name.charAt(0).toUpperCase() : 'U'}
                      </div>
                    )}
                    <span className="min-w-0 break-all text-left">{email || '소셜 로그인'}</span>
                  </div>
                </div>
              </>
            )}
          </div>

          {notice && !error && (
            <div role="status" className="mb-6 p-3.5 bg-amber-50 border border-amber-200 rounded-2xl text-xs text-amber-800">
              {notice}
            </div>
          )}

          {/* Error Banner */}
          {error && (
            <div className="mb-6 p-3.5 bg-red-50 border border-red-200 rounded-2xl flex items-start gap-3 text-xs text-red-700 animate-in fade-in duration-200">
              <AlertCircle className="w-4 h-4 text-red-500 shrink-0 mt-0.5" />
              <span>{error}</span>
            </div>
          )}

          {/* STEP 1: 이메일 입력 */}
          {step === 'email' && (
            <form onSubmit={handleEmailNext} className="space-y-6 animate-in fade-in duration-300">
              <div>
                <label className="block text-xs font-bold text-slate-700 mb-1.5">이메일 또는 휴대전화</label>
                <div className="relative">
                  <Mail className="w-4 h-4 text-slate-400 absolute left-3.5 top-3.5" />
                  <input
                    type="email"
                    required
                    autoFocus
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    placeholder="user@doro.local"
                    className="w-full pl-10 pr-4 py-3 bg-slate-50/80 border border-slate-200 rounded-xl text-sm focus:outline-hidden focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 transition-all font-medium"
                  />
                </div>
              </div>

              <div className="pt-2 flex items-center justify-between gap-4">
                <Link
                  to="/signup"
                  state={location.state}
                  className="text-xs font-bold text-indigo-600 hover:text-indigo-700 px-3 py-2 rounded-xl hover:bg-indigo-50 transition-colors"
                >
                  계정 만들기
                </Link>

                <button
                  type="submit"
                  disabled={emailLoading}
                  className="py-2.5 px-6 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl text-sm font-bold transition-all shadow-md shadow-indigo-500/25 flex items-center gap-2 cursor-pointer disabled:opacity-50"
                >
                  {emailLoading ? <Loader2 className="w-4 h-4 animate-spin" /> : '다음'}
                  {!emailLoading && <ArrowRight className="w-4 h-4" />}
                </button>
              </div>

              {socialProviders.length > 0 && (
                <div className="pt-2">
                  <div className="flex items-center gap-3 mb-4" role="separator" aria-label="또는">
                    <div className="h-px flex-1 bg-slate-200" />
                    <span className="text-[11px] font-bold text-slate-400">또는</span>
                    <div className="h-px flex-1 bg-slate-200" />
                  </div>
                  <div className="space-y-2.5">
                    {socialProviders.map((provider) => (
                      <button
                        key={provider.id}
                        type="button"
                        disabled={socialLoading || loading}
                        onClick={() => handleSocialStart(provider)}
                        className="w-full py-2.5 px-4 bg-white hover:bg-slate-50 border border-slate-200 rounded-xl text-sm font-bold text-slate-700 transition-colors flex items-center justify-center gap-2.5 cursor-pointer disabled:opacity-50"
                      >
                        {SOCIAL_ICONS[provider.id] ?? null}
                        {provider.name}(으)로 계속하기
                      </button>
                    ))}
                  </div>
                </div>
              )}
            </form>
          )}

          {/* STEP 2: 비밀번호 입력 (Google Style) */}
          {step === 'password' && (
            <form onSubmit={handlePasswordSubmit} className="space-y-6 animate-in fade-in slide-in-from-right-4 duration-300">
              <div>
                <label className="block text-xs font-bold text-slate-700 mb-1.5">비밀번호 입력</label>
                <div className="relative">
                  <Lock className="w-4 h-4 text-slate-400 absolute left-3.5 top-3.5" />
                  <input
                    type={showPassword ? 'text' : 'password'}
                    required
                    autoFocus
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    placeholder="••••••••"
                    className="w-full pl-10 pr-10 py-3 bg-slate-50/80 border border-slate-200 rounded-xl text-sm focus:outline-hidden focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 transition-all font-medium"
                  />
                  <button
                    type="button"
                    onClick={() => setShowPassword(!showPassword)}
                    className="absolute right-3.5 top-3.5 text-slate-400 hover:text-slate-600 cursor-pointer"
                  >
                    {showPassword ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                  </button>
                </div>
                <div className="mt-2 flex items-center justify-between">
                  <label className="flex items-center gap-2 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={showPassword}
                      onChange={(e) => setShowPassword(e.target.checked)}
                      className="rounded text-indigo-600 focus:ring-indigo-500 w-3.5 h-3.5"
                    />
                    <span className="text-xs text-slate-600 font-medium">비밀번호 표시</span>
                  </label>
                </div>
              </div>

              <div className="pt-2 flex items-center justify-between gap-4">
                <button
                  type="button"
                  onClick={handleBackToEmail}
                  className="py-2.5 px-4 text-xs font-bold text-slate-600 hover:text-slate-800 hover:bg-slate-100 rounded-xl transition-colors flex items-center gap-1.5 cursor-pointer"
                >
                  <ArrowLeft className="w-3.5 h-3.5" />
                  이전
                </button>

                <button
                  type="submit"
                  disabled={loading}
                  className="py-2.5 px-6 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl text-sm font-bold transition-all shadow-md shadow-indigo-500/25 flex items-center gap-2 disabled:opacity-50 cursor-pointer"
                >
                  {loading ? <Loader2 className="w-4 h-4 animate-spin" /> : '로그인'}
                  {!loading && <ArrowRight className="w-4 h-4" />}
                </button>
              </div>
            </form>
          )}

          {/* STEP 3: 2FA TOTP 인증 */}
          {step === '2fa' && (
            <form onSubmit={handle2faSubmit} className="space-y-6 animate-in fade-in slide-in-from-right-4 duration-300">
              <div className="p-4 bg-indigo-50/80 border border-indigo-100 rounded-2xl text-center">
                <KeyRound className="w-8 h-8 text-indigo-600 mx-auto mb-2" />
                <p className="text-xs text-slate-600 leading-relaxed font-medium">
                  스마트폰의 <strong>Google Authenticator</strong> 앱에 표시된 6자리 코드를 입력하세요.
                </p>
              </div>

              <div>
                <label className="block text-xs font-bold text-slate-700 mb-1.5 text-center">6자리 인증 코드</label>
                <input
                  type="text"
                  maxLength={6}
                  required
                  autoFocus
                  value={totpCode}
                  onChange={(e) => setTotpCode(e.target.value.replace(/\D/g, ''))}
                  placeholder="123456"
                  className="w-full py-3 px-4 text-center tracking-widest text-2xl font-mono font-bold bg-slate-50 border border-slate-200 rounded-xl focus:outline-hidden focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 transition-all"
                />
              </div>

              <div className="pt-2 flex items-center justify-between gap-3">
                <button
                  type="button"
                  onClick={() => {
                    setError(null);
                    if (socialSlot !== null) {
                      setSocialSlot(null);
                      setTempTicket('');
                    }
                    setStep(socialSlot !== null ? 'email' : 'password');
                  }}
                  className="flex-1 py-2.5 px-4 border border-slate-200 hover:bg-slate-50 rounded-xl text-xs font-bold text-slate-600 transition-colors cursor-pointer"
                >
                  뒤로가기
                </button>
                <button
                  type="submit"
                  disabled={twoFaLoading || totpCode.length !== 6}
                  className="flex-1 py-2.5 px-4 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl text-xs font-bold transition-all shadow-md shadow-indigo-500/25 flex items-center justify-center gap-1.5 disabled:opacity-50 cursor-pointer"
                >
                  {twoFaLoading ? <Loader2 className="w-4 h-4 animate-spin" /> : '인증 완료'}
                </button>
              </div>
            </form>
          )}
        </div>
      </div>
    </div>
  );
};

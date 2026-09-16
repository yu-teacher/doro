import React, { useState, useEffect } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { authApi } from '../api/authApi';
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

type LoginStep = 'email' | 'password' | '2fa';

export const LoginPage: React.FC = () => {
  const navigate = useNavigate();
  const { accounts, activeAccountIndex, addAccount } = useAuthStore();
  const activeAccount = accounts[activeAccountIndex] || accounts[0];

  useEffect(() => {
    if (activeAccount) {
      navigate('/account', { replace: true });
    }
  }, [activeAccount, navigate]);

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

  // 2FA states
  const [tempTicket, setTempTicket] = useState('');
  const [totpCode, setTotpCode] = useState('');
  const [twoFaLoading, setTwoFaLoading] = useState(false);

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
      const res = await authApi.login({ email: email.trim(), password });

      if (res.tokens && res.tokens.accessToken) {
        const decoded = parseJwtPayload(res.tokens.accessToken);
        addAccount({
          userId: decoded?.sub || '',
          email: email.trim(),
          fullName: accountInfo?.name || email.trim().split('@')[0],
          profileImageUrl: accountInfo?.profileImageUrl || null,
          role: decoded?.role || 'USER',
          accessToken: res.tokens.accessToken,
          refreshToken: res.tokens.refreshToken,
          sessionId: res.tokens.sessionId,
          userIndex: res.tokens.userIndex ?? 0,
        });
        navigate('/account');
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
      const res = await authApi.loginWith2fa({ tempTicket, code: totpCode });

      if (res && res.accessToken) {
        const decoded = parseJwtPayload(res.accessToken);
        addAccount({
          userId: decoded?.sub || '',
          email: email.trim(),
          fullName: accountInfo?.name || email.trim().split('@')[0],
          profileImageUrl: accountInfo?.profileImageUrl || null,
          role: decoded?.role || 'USER',
          accessToken: res.accessToken,
          refreshToken: res.refreshToken,
          sessionId: res.sessionId,
          userIndex: res.userIndex ?? 0,
        });
        navigate('/account');
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
                <div className="mt-3 inline-flex items-center">
                  <button
                    type="button"
                    onClick={handleBackToEmail}
                    className="inline-flex items-center gap-2 px-3.5 py-1.5 rounded-full border border-slate-200 bg-slate-50/80 hover:bg-slate-100/80 text-xs font-semibold text-slate-700 transition-colors group cursor-pointer shadow-2xs"
                    title="다른 계정으로 로그인"
                  >
                    {accountInfo?.profileImageUrl ? (
                      <img src={accountInfo.profileImageUrl} alt="Profile" className="w-5 h-5 rounded-full object-cover" />
                    ) : (
                      <div className="w-5 h-5 rounded-full bg-gradient-to-tr from-indigo-600 to-violet-500 text-white flex items-center justify-center text-[10px] font-bold">
                        {accountInfo?.name ? accountInfo.name.charAt(0).toUpperCase() : 'U'}
                      </div>
                    )}
                    <span>{email}</span>
                    <ChevronDown className="w-3.5 h-3.5 text-slate-400 group-hover:text-slate-600 transition-transform" />
                  </button>
                </div>
              </>
            )}

            {step === '2fa' && (
              <>
                <h1 className="text-2xl font-extrabold text-slate-900 tracking-tight">2단계 인증</h1>
                {/* Account Chip */}
                <div className="mt-3 inline-flex items-center">
                  <div className="inline-flex items-center gap-2 px-3.5 py-1.5 rounded-full border border-indigo-100 bg-indigo-50/50 text-xs font-semibold text-indigo-900">
                    {accountInfo?.profileImageUrl ? (
                      <img src={accountInfo.profileImageUrl} alt="Profile" className="w-5 h-5 rounded-full object-cover" />
                    ) : (
                      <div className="w-5 h-5 rounded-full bg-indigo-600 text-white flex items-center justify-center text-[10px] font-bold">
                        {accountInfo?.name ? accountInfo.name.charAt(0).toUpperCase() : 'U'}
                      </div>
                    )}
                    <span>{email}</span>
                  </div>
                </div>
              </>
            )}
          </div>

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
                <div className="mt-2 text-left">
                  <span className="text-[11px] text-indigo-600 hover:text-indigo-700 font-semibold cursor-pointer">
                    이메일을 잊으셨나요?
                  </span>
                </div>
              </div>

              <div className="pt-2 flex items-center justify-between gap-4">
                <Link
                  to="/signup"
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
                  <span className="text-[11px] text-indigo-600 hover:text-indigo-700 font-semibold cursor-pointer">
                    비밀번호를 잊으셨나요?
                  </span>
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
                  onClick={() => setStep('password')}
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

          {/* Bottom Security notice */}
          <div className="mt-8 pt-6 border-t border-slate-100 text-center">
            <p className="text-[11px] text-slate-400">
              내 컴퓨터가 아닌 경우 게스트 모드를 사용하여 비공개로 로그인하세요.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
};

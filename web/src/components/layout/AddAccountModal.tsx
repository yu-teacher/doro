import React, { useState } from 'react';
import { authApi } from '../../api/authApi';
import { useAuthStore } from '../../store/authStore';
import {
  Shield,
  Lock,
  Mail,
  AlertCircle,
  X,
  Loader2,
  ArrowRight,
  ArrowLeft,
  ChevronDown,
  Eye,
  EyeOff,
  KeyRound,
} from 'lucide-react';
import { getErrorMessage } from '../../utils/errorUtils';
import { parseJwtPayload } from '../../utils/jwtUtils';
import { AccountLookupResponse } from '../../types/auth';

interface AddAccountModalProps {
  isOpen: boolean;
  onClose: () => void;
}

type ModalStep = 'email' | 'password' | '2fa';

export const AddAccountModal: React.FC<AddAccountModalProps> = ({ isOpen, onClose }) => {
  const { addAccount } = useAuthStore();

  const [step, setStep] = useState<ModalStep>('email');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [loading, setLoading] = useState(false);
  const [emailLoading, setEmailLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [accountInfo, setAccountInfo] = useState<AccountLookupResponse | null>(null);

  // 2FA states
  const [tempTicket, setTempTicket] = useState('');
  const [totpCode, setTotpCode] = useState('');
  const [twoFaLoading, setTwoFaLoading] = useState(false);

  if (!isOpen) return null;

  const handleReset = () => {
    setEmail('');
    setPassword('');
    setStep('email');
    setError(null);
    setAccountInfo(null);
    setTempTicket('');
    setTotpCode('');
    onClose();
  };

  // 1단계: Google 스타일 계정 사전 검증 (Account Lookup)
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

  // 2단계: 비밀번호 제출 및 계정 추가
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
          userIndex: res.tokens.userIndex ?? 1,
        });
        handleReset();
      } else if (res.requires2fa && res.tempTicket) {
        setTempTicket(res.tempTicket);
        setStep('2fa');
      } else {
        setError('계정 추가에 실패했습니다.');
      }
    } catch (err: unknown) {
      setError(getErrorMessage(err, '비밀번호가 올바르지 않습니다. 다시 확인해 주세요.'));
    } finally {
      setLoading(false);
    }
  };

  // 3단계: 2FA TOTP 검증 및 계정 추가
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
          userIndex: res.userIndex ?? 1,
        });
        handleReset();
      } else {
        setError('2FA 인증에 실패했습니다.');
      }
    } catch (err: unknown) {
      setError(getErrorMessage(err, '인증 코드가 올바르지 않거나 만료되었습니다.'));
    } finally {
      setTwoFaLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/40 backdrop-blur-xs">
      <div className="glass-card w-full max-w-md rounded-3xl p-6 sm:p-8 shadow-2xl animate-in fade-in zoom-in-95 duration-200">
        {/* Header */}
        <div className="flex items-center justify-between pb-4 border-b border-slate-100">
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 rounded-xl bg-indigo-50 text-indigo-600 flex items-center justify-center">
              <Shield className="w-4 h-4" />
            </div>
            <h3 className="text-base font-bold text-slate-800">다른 계정 추가 로그인</h3>
          </div>
          <button onClick={handleReset} className="text-slate-400 hover:text-slate-600 p-1.5 rounded-full hover:bg-slate-100 cursor-pointer">
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Error Banner */}
        {error && (
          <div className="mt-4 p-3 bg-red-50 border border-red-200 rounded-2xl flex items-start gap-2.5 text-xs text-red-700 animate-in fade-in">
            <AlertCircle className="w-4 h-4 text-red-500 shrink-0 mt-0.5" />
            <span>{error}</span>
          </div>
        )}

        {/* STEP 1: 이메일 입력 */}
        {step === 'email' && (
          <form onSubmit={handleEmailNext} className="mt-5 space-y-4 animate-in fade-in">
            <div>
              <label className="block text-xs font-bold text-slate-600 mb-1.5">이메일 주소</label>
              <div className="relative">
                <Mail className="w-4 h-4 text-slate-400 absolute left-3.5 top-3.5" />
                <input
                  type="email"
                  required
                  autoFocus
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder="user@doro.local"
                  className="w-full pl-10 pr-4 py-2.5 bg-slate-50 border border-slate-200 rounded-xl text-sm focus:outline-hidden focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 transition-all font-medium"
                />
              </div>
            </div>

            <div className="pt-2 flex gap-2">
              <button
                type="button"
                onClick={handleReset}
                className="flex-1 py-2.5 px-4 border border-slate-200 hover:bg-slate-50 rounded-xl text-xs font-bold text-slate-600 transition-colors cursor-pointer"
              >
                취소
              </button>
              <button
                type="submit"
                disabled={emailLoading}
                className="flex-1 py-2.5 px-4 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl text-xs font-bold transition-all shadow-md shadow-indigo-500/20 flex items-center justify-center gap-1.5 cursor-pointer disabled:opacity-50"
              >
                {emailLoading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : '다음'}
                {!emailLoading && <ArrowRight className="w-3.5 h-3.5" />}
              </button>
            </div>
          </form>
        )}

        {/* STEP 2: 비밀번호 입력 (Google Style Account Chip) */}
        {step === 'password' && (
          <form onSubmit={handlePasswordSubmit} className="mt-5 space-y-4 animate-in fade-in slide-in-from-right-4">
            {/* Account Pill Chip */}
            <div className="flex flex-col items-center gap-1.5">
              <span className="text-sm font-bold text-slate-800">
                {accountInfo?.name ? `${accountInfo.name}님 환영합니다` : '비밀번호 입력'}
              </span>
              <button
                type="button"
                onClick={() => setStep('email')}
                className="inline-flex items-center gap-2 px-3 py-1 rounded-full border border-slate-200 bg-slate-50 hover:bg-slate-100 text-xs font-semibold text-slate-700 transition-colors group cursor-pointer"
                title="다른 계정 입력"
              >
                {accountInfo?.profileImageUrl ? (
                  <img src={accountInfo.profileImageUrl} alt="Profile" className="w-4 h-4 rounded-full object-cover" />
                ) : (
                  <div className="w-4 h-4 rounded-full bg-indigo-600 text-white flex items-center justify-center text-[9px] font-bold">
                    {accountInfo?.name ? accountInfo.name.charAt(0).toUpperCase() : 'U'}
                  </div>
                )}
                <span>{email}</span>
                <ChevronDown className="w-3 h-3 text-slate-400 group-hover:text-slate-600" />
              </button>
            </div>

            <div>
              <label className="block text-xs font-bold text-slate-600 mb-1.5">비밀번호 입력</label>
              <div className="relative">
                <Lock className="w-4 h-4 text-slate-400 absolute left-3.5 top-3.5" />
                <input
                  type={showPassword ? 'text' : 'password'}
                  required
                  autoFocus
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="••••••••"
                  className="w-full pl-10 pr-10 py-2.5 bg-slate-50 border border-slate-200 rounded-xl text-sm focus:outline-hidden focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 transition-all font-medium"
                />
                <button
                  type="button"
                  onClick={() => setShowPassword(!showPassword)}
                  className="absolute right-3.5 top-3.5 text-slate-400 hover:text-slate-600 cursor-pointer"
                >
                  {showPassword ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                </button>
              </div>
            </div>

            <div className="pt-2 flex gap-2">
              <button
                type="button"
                onClick={() => setStep('email')}
                className="flex-1 py-2.5 px-4 border border-slate-200 hover:bg-slate-50 rounded-xl text-xs font-bold text-slate-600 transition-colors flex items-center justify-center gap-1 cursor-pointer"
              >
                <ArrowLeft className="w-3.5 h-3.5" />
                이전
              </button>
              <button
                type="submit"
                disabled={loading}
                className="flex-1 py-2.5 px-4 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl text-xs font-bold transition-all shadow-md shadow-indigo-500/20 flex items-center justify-center gap-1.5 disabled:opacity-50 cursor-pointer"
              >
                {loading ? <Loader2 className="w-4 h-4 animate-spin" /> : '로그인'}
              </button>
            </div>
          </form>
        )}

        {/* STEP 3: 2FA */}
        {step === '2fa' && (
          <form onSubmit={handle2faSubmit} className="mt-5 space-y-4 animate-in fade-in slide-in-from-right-4">
            {/* Account Pill Chip */}
            <div className="flex justify-center mb-2">
              <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full border border-indigo-100 bg-indigo-50/50 text-xs font-semibold text-indigo-900">
                {accountInfo?.profileImageUrl ? (
                  <img src={accountInfo.profileImageUrl} alt="Profile" className="w-4 h-4 rounded-full object-cover" />
                ) : (
                  <div className="w-4 h-4 rounded-full bg-indigo-600 text-white flex items-center justify-center text-[9px] font-bold">
                    {accountInfo?.name ? accountInfo.name.charAt(0).toUpperCase() : 'U'}
                  </div>
                )}
                <span>{email}</span>
              </div>
            </div>

            <div className="p-3 bg-indigo-50/80 border border-indigo-100 rounded-xl text-center">
              <KeyRound className="w-6 h-6 text-indigo-600 mx-auto mb-1" />
              <p className="text-[11px] text-slate-600 font-medium">인증기 앱의 6자리 코드를 입력하세요.</p>
            </div>

            <div>
              <input
                type="text"
                maxLength={6}
                required
                autoFocus
                value={totpCode}
                onChange={(e) => setTotpCode(e.target.value.replace(/\D/g, ''))}
                placeholder="123456"
                className="w-full py-2.5 px-4 text-center tracking-widest text-xl font-mono font-bold bg-slate-50 border border-slate-200 rounded-xl focus:outline-hidden focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 transition-all"
              />
            </div>

            <div className="pt-2 flex gap-2">
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
                className="flex-1 py-2.5 px-4 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl text-xs font-bold transition-all shadow-md shadow-indigo-500/20 flex items-center justify-center gap-1.5 disabled:opacity-50 cursor-pointer"
              >
                {twoFaLoading ? <Loader2 className="w-4 h-4 animate-spin" /> : '인증 완료'}
              </button>
            </div>
          </form>
        )}
      </div>
    </div>
  );
};

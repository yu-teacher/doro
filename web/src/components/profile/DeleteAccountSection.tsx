import React, { useState } from 'react';
import { AlertTriangle, Loader2, Trash2 } from 'lucide-react';
import { authApi } from '../../api/authApi';
import { UserRole } from '../../types/auth';
import { getErrorMessage } from '../../utils/errorUtils';
import {
  ACCOUNT_DELETION_GRACE_DAYS,
  canRequestDeletion,
  validateDeletionForm,
} from '../../utils/accountDeletion';

interface Props {
  role: UserRole | undefined;
  hasTotp: boolean;
  /** 서버가 탈퇴를 접수한 뒤 호출된다. 호출 측이 로컬 세션을 정리하고 화면을 옮긴다. */
  onRequested: (scheduledPurgeAt: string) => void;
}

export const DeleteAccountSection: React.FC<Props> = ({ role, hasTotp, onRequested }) => {
  const [open, setOpen] = useState(false);
  const [confirmed, setConfirmed] = useState(false);
  const [password, setPassword] = useState('');
  const [totpCode, setTotpCode] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const allowed = canRequestDeletion(role);

  const close = () => {
    setOpen(false);
    setConfirmed(false);
    setPassword('');
    setTotpCode('');
    setError(null);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const problem = validateDeletionForm({ confirmed, password, hasTotp, totpCode });
    if (problem) {
      setError(problem);
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      const result = await authApi.requestAccountDeletion({
        password,
        totpCode: hasTotp ? totpCode.trim() : undefined,
      });
      onRequested(result.scheduledPurgeAt);
    } catch (err: unknown) {
      setError(getErrorMessage(err, '탈퇴 요청에 실패했습니다. 잠시 후 다시 시도해 주세요.'));
      setSubmitting(false);
    }
  };

  return (
    <div className="glass-card google-card-shadow rounded-3xl p-6 sm:p-8 border border-red-100">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h3 className="text-lg font-bold text-red-700">계정 삭제</h3>
          <p className="text-xs text-slate-500 mt-1">Doro 계정과 연결된 개인정보를 삭제합니다.</p>
        </div>
        <div className="w-10 h-10 rounded-xl bg-red-50 text-red-600 flex items-center justify-center shrink-0">
          <Trash2 className="w-5 h-5" />
        </div>
      </div>

      <ul className="mt-4 space-y-1.5 text-xs text-slate-600 list-disc pl-5">
        <li>요청하는 즉시 모든 기기에서 로그아웃됩니다.</li>
        <li>
          {ACCOUNT_DELETION_GRACE_DAYS}일 안에 같은 계정으로 다시 로그인하면 탈퇴가 취소됩니다.
        </li>
        <li>
          {ACCOUNT_DELETION_GRACE_DAYS}일이 지나면 이름·이메일·프로필 사진 등 개인정보가 영구 삭제되며 되돌릴 수 없습니다. 같은 이메일로 다시 가입할 수는 있지만 이전 데이터는 이어지지 않습니다.
        </li>
        <li>
          블로그에 쓴 글과 댓글은 남고 작성자가 &lsquo;탈퇴한 사용자&rsquo;로 표시됩니다. 지우고 싶은 글은 탈퇴 전에 블로그에서 직접 삭제해 주세요.
        </li>
      </ul>

      {!allowed ? (
        <div className="mt-5 p-3.5 bg-amber-50 border border-amber-200 rounded-2xl text-xs text-amber-800 flex items-start gap-2">
          <AlertTriangle className="w-4 h-4 text-amber-600 shrink-0 mt-0.5" />
          <span>관리자 계정은 다른 관리자가 역할을 일반 사용자로 변경한 뒤에 탈퇴할 수 있습니다.</span>
        </div>
      ) : !open ? (
        <button
          type="button"
          onClick={() => setOpen(true)}
          className="mt-5 px-4 py-2 rounded-xl border border-red-200 text-red-600 hover:bg-red-50 text-xs font-bold transition-colors cursor-pointer"
        >
          계정 삭제 진행
        </button>
      ) : (
        <form onSubmit={handleSubmit} className="mt-5 space-y-3 p-4 bg-red-50/40 border border-red-100 rounded-2xl">
          {error && (
            <div role="alert" className="p-2.5 bg-red-50 border border-red-200 rounded-xl text-xs text-red-700">
              {error}
            </div>
          )}
          <label className="flex items-start gap-2 text-xs text-slate-700 cursor-pointer">
            <input
              type="checkbox"
              checked={confirmed}
              onChange={(e) => setConfirmed(e.target.checked)}
              className="mt-0.5"
            />
            <span>위 안내를 모두 확인했고, 계정을 삭제하는 데 동의합니다.</span>
          </label>
          <div>
            <label htmlFor="delete-account-password" className="block text-[11px] font-bold text-slate-600 mb-1">
              비밀번호
            </label>
            <input
              id="delete-account-password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="w-full px-3 py-2 bg-white border border-slate-200 rounded-xl text-xs focus:ring-2 focus:ring-red-500/20 focus:border-red-500 font-medium"
            />
          </div>
          {hasTotp && (
            <div>
              <label htmlFor="delete-account-totp" className="block text-[11px] font-bold text-slate-600 mb-1">
                2단계 인증 코드 (6자리)
              </label>
              <input
                id="delete-account-totp"
                type="text"
                inputMode="numeric"
                autoComplete="one-time-code"
                maxLength={6}
                value={totpCode}
                onChange={(e) => setTotpCode(e.target.value)}
                className="w-full px-3 py-2 bg-white border border-slate-200 rounded-xl text-xs focus:ring-2 focus:ring-red-500/20 focus:border-red-500 font-mono tracking-widest"
              />
            </div>
          )}
          <div className="pt-1 flex gap-2">
            <button
              type="button"
              onClick={close}
              disabled={submitting}
              className="flex-1 py-2 px-3 border border-slate-200 hover:bg-white rounded-xl text-xs font-bold text-slate-600 cursor-pointer disabled:opacity-50"
            >
              취소
            </button>
            <button
              type="submit"
              disabled={submitting}
              className="flex-1 py-2 px-3 bg-red-600 hover:bg-red-700 text-white rounded-xl text-xs font-bold transition-all shadow-sm flex items-center justify-center gap-1.5 cursor-pointer disabled:opacity-50"
            >
              {submitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : '계정 삭제'}
            </button>
          </div>
        </form>
      )}
    </div>
  );
};

import { UserRole } from '../types/auth';

/** 탈퇴 요청 후 개인정보가 영구 삭제되기까지의 유예 기간(일). 서버 기본값(doro.iam.account-deletion.grace-days)과 같다. */
export const ACCOUNT_DELETION_GRACE_DAYS = 30;

/** 관리자 계정은 역할을 일반 사용자로 내린 뒤에만 탈퇴할 수 있다. (서버도 같은 규칙으로 거부한다) */
export function canRequestDeletion(role: UserRole | undefined): boolean {
  return role === undefined || role === 'USER';
}

/** 탈퇴 요청 직후 로그인 화면에 보여 줄 안내 문구. */
export function deletionNotice(scheduledPurgeAt: string): string {
  const date = new Date(scheduledPurgeAt);
  const when = Number.isNaN(date.getTime()) ? `${ACCOUNT_DELETION_GRACE_DAYS}일 뒤` : `${date.toLocaleDateString('ko-KR')}까지`;
  return `탈퇴가 접수되었습니다. ${when} 같은 계정으로 다시 로그인하면 탈퇴가 취소되고, 그 뒤에는 개인정보가 영구 삭제됩니다.`;
}

export interface DeletionForm {
  confirmed: boolean;
  password: string;
  hasTotp: boolean;
  totpCode: string;
}

/** 제출 전 입력 검증. 문제가 없으면 null. */
export function validateDeletionForm(form: DeletionForm): string | null {
  if (!form.confirmed) return '삭제 안내를 확인했다는 항목에 체크해 주세요.';
  if (form.password.length === 0) return '비밀번호를 입력해 주세요.';
  if (form.hasTotp && !/^\d{6}$/.test(form.totpCode.trim())) return '인증기 앱의 6자리 코드를 입력해 주세요.';
  return null;
}

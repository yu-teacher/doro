import { describe, expect, it } from 'vitest';
import {
  ACCOUNT_DELETION_GRACE_DAYS,
  canRequestDeletion,
  deletionNotice,
  validateDeletionForm,
} from './accountDeletion';

describe('canRequestDeletion', () => {
  it('일반 사용자는 탈퇴할 수 있고 관리자는 역할을 내리기 전에는 할 수 없다', () => {
    expect(canRequestDeletion('USER')).toBe(true);
    expect(canRequestDeletion(undefined)).toBe(true);
    expect(canRequestDeletion('ADMIN')).toBe(false);
    expect(canRequestDeletion('SUPER_ADMIN')).toBe(false);
  });
});

describe('validateDeletionForm', () => {
  const base = { confirmed: true, password: 'Password123!', hasTotp: false, totpCode: '' };

  it('안내 확인과 비밀번호가 있으면 통과한다', () => {
    expect(validateDeletionForm(base)).toBeNull();
  });

  it('안내를 확인하지 않았거나 비밀번호가 비어 있으면 막는다', () => {
    expect(validateDeletionForm({ ...base, confirmed: false })).not.toBeNull();
    expect(validateDeletionForm({ ...base, password: '' })).not.toBeNull();
  });

  it('2FA 사용자는 6자리 숫자 코드가 있어야 한다', () => {
    const withTotp = { ...base, hasTotp: true };
    expect(validateDeletionForm({ ...withTotp, totpCode: '' })).not.toBeNull();
    expect(validateDeletionForm({ ...withTotp, totpCode: '12345' })).not.toBeNull();
    expect(validateDeletionForm({ ...withTotp, totpCode: 'abcdef' })).not.toBeNull();
    expect(validateDeletionForm({ ...withTotp, totpCode: ' 123456 ' })).toBeNull();
  });

  it('2FA 를 쓰지 않으면 코드는 보지 않는다', () => {
    expect(validateDeletionForm({ ...base, totpCode: 'garbage' })).toBeNull();
  });
});

describe('deletionNotice', () => {
  it('처리 예정일과 취소 방법을 알려 준다', () => {
    const notice = deletionNotice('2026-11-06T01:00:00Z');
    expect(notice).toContain('다시 로그인');
    expect(notice).toContain('2026');
  });

  it('날짜를 해석할 수 없으면 유예 기간(일)로 안내한다', () => {
    expect(deletionNotice('not-a-date')).toContain(`${ACCOUNT_DELETION_GRACE_DAYS}일`);
  });
});

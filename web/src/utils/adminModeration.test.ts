import { describe, expect, it } from 'vitest';
import { MAX_SUSPENSION_REASON_LENGTH, moderationFor, validateSuspensionReason } from './adminModeration';

const admin = { id: 'a', isSuperAdmin: false };
const superAdmin = { id: 's', isSuperAdmin: true };
const user = (over: Record<string, unknown> = {}) => ({ id: 'u', role: 'USER' as const, status: 'ACTIVE', locked: false, ...over });

describe('moderationFor', () => {
  it('일반 관리자는 일반 회원을 정지할 수 있다', () => {
    expect(moderationFor(admin, user())).toEqual({ canSuspend: true, canReinstate: false, canUnlock: false });
  });

  it('정지 중인 계정은 해제만 할 수 있다', () => {
    expect(moderationFor(admin, user({ status: 'SUSPENDED' }))).toEqual({ canSuspend: false, canReinstate: true, canUnlock: false });
  });

  it('잠긴 계정은 잠금 해제를 보여 준다', () => {
    expect(moderationFor(admin, user({ locked: true })).canUnlock).toBe(true);
  });

  it('본인은 정지할 수 없다', () => {
    expect(moderationFor(superAdmin, user({ id: 's', role: 'SUPER_ADMIN' })).canSuspend).toBe(false);
    expect(moderationFor(admin, user({ id: 'a' })).canSuspend).toBe(false);
  });

  it('최고 관리자는 누구도 정지할 수 없다', () => {
    expect(moderationFor(superAdmin, user({ role: 'SUPER_ADMIN' })).canSuspend).toBe(false);
  });

  it('관리자 계정은 최고 관리자만 정지·해제·잠금 해제한다', () => {
    const target = user({ role: 'ADMIN' });
    expect(moderationFor(admin, target)).toEqual({ canSuspend: false, canReinstate: false, canUnlock: false });
    expect(moderationFor(admin, user({ role: 'ADMIN', status: 'SUSPENDED', locked: true }))).toEqual({
      canSuspend: false, canReinstate: false, canUnlock: false,
    });
    expect(moderationFor(superAdmin, target).canSuspend).toBe(true);
    expect(moderationFor(superAdmin, user({ role: 'ADMIN', status: 'SUSPENDED' })).canReinstate).toBe(true);
  });

  it('탈퇴 유예·탈퇴한 계정은 정지 대상이 아니다', () => {
    expect(moderationFor(admin, user({ status: 'PENDING_DELETION' })).canSuspend).toBe(false);
    expect(moderationFor(admin, user({ status: 'DELETED' })).canSuspend).toBe(false);
  });
});

describe('validateSuspensionReason', () => {
  it('취소하면 null, 비어 있거나 길면 오류, 아니면 다듬은 사유', () => {
    expect(validateSuspensionReason(null)).toBeNull();
    expect(validateSuspensionReason('   ')).toEqual({ error: '정지 사유를 입력해 주세요.' });
    expect(validateSuspensionReason('x'.repeat(MAX_SUSPENSION_REASON_LENGTH + 1))).toHaveProperty('error');
    expect(validateSuspensionReason('  스팸  ')).toEqual({ reason: '스팸' });
    expect(validateSuspensionReason('x'.repeat(MAX_SUSPENSION_REASON_LENGTH))).toHaveProperty('reason');
  });
});

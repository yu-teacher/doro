import type { UserProfileData } from '../types/auth';

export const MAX_SUSPENSION_REASON_LENGTH = 200;

export interface ModerationActor {
  id: string;
  isSuperAdmin: boolean;
}

export interface ModerationOptions {
  canSuspend: boolean;
  canReinstate: boolean;
  canUnlock: boolean;
}

/**
 * 관리자 목록의 한 사용자에 대해 어떤 조치 버튼을 보여 줄지. 서버(AccountSuspensionService)가 같은 규칙으로 최종 판정하므로
 * 이것은 누르면 거부될 버튼을 숨기기 위한 것이다: 본인과 최고 관리자는 정지할 수 없고, 관리자·최고 관리자 계정은 최고 관리자만 처리한다.
 */
export function moderationFor(
  actor: ModerationActor,
  target: Pick<UserProfileData, 'id' | 'role' | 'status' | 'locked'>,
): ModerationOptions {
  const isSelf = actor.id === target.id;
  const privilegedTarget = target.role === 'ADMIN' || target.role === 'SUPER_ADMIN';
  const mayModerate = !privilegedTarget || actor.isSuperAdmin;
  return {
    canSuspend: mayModerate && !isSelf && target.role !== 'SUPER_ADMIN' && target.status === 'ACTIVE',
    canReinstate: mayModerate && target.status === 'SUSPENDED',
    canUnlock: mayModerate && target.locked === true,
  };
}

/** 정지 사유 입력값 검증. 올바르면 다듬은 사유를, 아니면 null 과 안내 문구를 돌려준다. */
export function validateSuspensionReason(raw: string | null): { reason: string } | { error: string } | null {
  if (raw === null) {
    return null; // 입력 취소
  }
  const reason = raw.trim();
  if (!reason) {
    return { error: '정지 사유를 입력해 주세요.' };
  }
  if (reason.length > MAX_SUSPENSION_REASON_LENGTH) {
    return { error: `정지 사유는 ${MAX_SUSPENSION_REASON_LENGTH}자 이내로 입력해 주세요.` };
  }
  return { reason };
}

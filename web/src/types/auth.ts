export type UserRole = 'USER' | 'ADMIN' | 'SUPER_ADMIN';

export interface UserProfile {
  userId: string;
  email: string;
  fullName: string;
  userIndex: number;
}

export interface UserProfileData {
  id: string;
  email: string;
  name: string;
  profileImageUrl?: string | null;
  status: string;
  role: UserRole;
  hasTotp: boolean;
  createdAt: string;
  /** 관리자 목록에서만 채워진다: 정지 시각·사유, 비밀번호 실패 잠금 여부. */
  suspendedAt?: string | null;
  suspensionReason?: string | null;
  locked?: boolean;
}

export interface AuthAccount {
  userId: string;
  email: string;
  fullName: string;
  profileImageUrl?: string | null;
  role?: UserRole;
  accessToken: string;
  refreshToken: string;
  sessionId?: string;
  userIndex: number;
}

export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
  sessionId: string;
  userIndex: number;
}

export interface LoginData {
  requires2fa: boolean;
  tempTicket?: string;
  tokens?: TokenResponse;
}

/** 회원탈퇴 요청 결과. 이 시각이 지나면 개인정보가 영구 삭제되고, 그 전에 다시 로그인하면 취소된다. */
export interface AccountDeletionResult {
  scheduledPurgeAt: string;
}

export interface SessionResponseDto {
  sessionId: string;
  userId: string;
  userIndex: number;
  deviceInfo: string;
  ipAddress: string;
  isActive: boolean;
  lastActiveAt: string;
  expiresAt: string;
  createdAt: string;
}

export interface TotpSetupData {
  secret: string;
  qrUri: string;
}

export interface AccountLookupResponse {
  email: string;
  name: string;
  profileImageUrl?: string | null;
}

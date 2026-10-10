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
  /** 메모리에서만 쓴다(저장하지 않는다). 새로고침 직후에는 비어 있고, 첫 요청이 쿠키로 새로 받는다. */
  accessToken: string;
  /**
   * 예전 방식(localStorage 에 저장)으로 로그인한 계정에만 남아 있는 리프레시 토큰. 한 번 쿠키로 바꾸면 지운다.
   * 새로 로그인한 계정에는 없다: 리프레시 토큰은 HttpOnly 쿠키에만 있어 JavaScript 가 읽을 수 없다.
   */
  refreshToken?: string;
  /** 이 계정의 리프레시 토큰 쿠키 슬롯(0~4). 서버가 슬롯별 쿠키(doro_rt_<슬롯>)로 계정을 구분한다. */
  slot: number;
  sessionId?: string;
  userIndex: number;
}

export interface TokenResponse {
  accessToken: string;
  /** 쿠키 방식에서는 응답에 없다. */
  refreshToken?: string | null;
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

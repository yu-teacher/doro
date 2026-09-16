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

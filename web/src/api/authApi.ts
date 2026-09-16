import { apiClient } from './client';
import { LoginData, TokenResponse, TotpSetupData, SessionResponseDto, UserProfileData } from '../types/auth';

export const authApi = {
  // 1. 회원가입
  signUp: async (data: { email: string; password: string; fullName: string }) => {
    const response = await apiClient.post<{ success: boolean; data: { userId: string } }>('/api/v1/auth/signup', {
      email: data.email,
      password: data.password,
      name: data.fullName,
    });
    return response.data.data;
  },

  // 1-1. 계정 사전 조회 (Google 스타일 Account Lookup)
  lookupAccount: async (email: string) => {
    const response = await apiClient.post<{ success: boolean; data: { email: string; name: string; profileImageUrl?: string | null } }>(
      '/api/v1/auth/lookup',
      { email }
    );
    return response.data.data;
  },

  // 2. 로그인 (1차 또는 일반 로그인)
  login: async (data: { email: string; password: string }) => {
    const response = await apiClient.post<{ success: boolean; data: LoginData }>('/api/v1/auth/login', data);
    return response.data.data;
  },

  // 3. 2FA 로그인 완료 (2차 OTP 입력)
  loginWith2fa: async (data: { tempTicket: string; code: string }) => {
    const response = await apiClient.post<{ success: boolean; data: TokenResponse }>('/api/v1/auth/2fa/login', data);
    return response.data.data;
  },

  // 4. 내 프로필 정보 조회
  getProfile: async () => {
    const response = await apiClient.get<{ success: boolean; data: UserProfileData }>('/api/v1/users/me');
    return response.data.data;
  },

  // 5. 내 프로필 정보 수정 (이름, 프로필 사진)
  updateProfile: async (data: { name?: string; profileImageUrl?: string | null }) => {
    const response = await apiClient.patch<{ success: boolean; data: UserProfileData }>('/api/v1/users/me', data);
    return response.data.data;
  },

  // 6. 비밀번호 변경
  changePassword: async (data: { currentPassword: string; newPassword: string }) => {
    const response = await apiClient.put<{ success: boolean }>('/api/v1/users/me/password', data);
    return response.data;
  },

  // 7. 2FA 등록 시작 (QR 코드 URI 발급)
  setup2fa: async () => {
    const response = await apiClient.post<{ success: boolean; data: TotpSetupData }>('/api/v1/auth/2fa/setup');
    return response.data.data;
  },

  // 8. 2FA 활성화 확정 (첫 OTP 검증)
  enable2fa: async (code: string) => {
    const response = await apiClient.post<{ success: boolean }>('/api/v1/auth/2fa/verify', { code });
    return response.data;
  },

  // 8-1. 2FA 비활성화 (해제)
  disable2fa: async () => {
    const response = await apiClient.post<{ success: boolean }>('/api/v1/auth/2fa/disable');
    return response.data;
  },

  // 9. 활성 세션 목록 조회
  getSessions: async () => {
    const response = await apiClient.get<{ success: boolean; data: SessionResponseDto[] }>('/api/v1/sessions');
    return response.data.data;
  },

  // 10. 특정 세션 원격 킬스위치 (강제 로그아웃)
  revokeSession: async (sessionId: string) => {
    const response = await apiClient.delete<{ success: boolean }>(`/api/v1/sessions/${sessionId}`);
    return response.data;
  },

  // 11. 다른 모든 세션 일괄 로그아웃 (현재 세션 제외)
  revokeOtherSessions: async (currentSessionId: string) => {
    const response = await apiClient.post<{ success: boolean }>(`/api/v1/sessions/revoke-others?currentSessionId=${currentSessionId}`);
    return response.data;
  },

  // 12. 로그아웃
  logout: async (sessionId: string) => {
    const response = await apiClient.post<{ success: boolean }>(`/api/v1/auth/logout?sessionId=${sessionId}`);
    return response.data;
  },

  // 13. 관리자 전용 사용자 목록 조회 (ADMIN, SUPER_ADMIN)
  getAdminUsers: async () => {
    const response = await apiClient.get<{ success: boolean; data: UserProfileData[] }>('/api/v1/admin/users');
    return response.data.data;
  },

  // 14. 슈퍼 어드민 전용 사용자 등급 변경 (SUPER_ADMIN)
  updateUserRole: async (userId: string, role: string) => {
    const response = await apiClient.patch<{ success: boolean; data: UserProfileData }>(`/api/v1/admin/users/${userId}/role`, { role });
    return response.data.data;
  },

  // 15. 슈퍼 어드민 전용 특정 사용자 2FA 초기화 (SUPER_ADMIN)
  resetUserTwoFactor: async (userId: string) => {
    const response = await apiClient.delete<{ success: boolean }>(`/api/v1/admin/users/${userId}/2fa`);
    return response.data;
  },
};

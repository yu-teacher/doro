import React, { useState, useEffect } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '../store/authStore';
import { authApi } from '../api/authApi';
import { SessionResponseDto, TotpSetupData, UserProfileData } from '../types/auth';
import { AvatarModal } from '../components/profile/AvatarModal';
import { DeleteAccountSection } from '../components/profile/DeleteAccountSection';
import { deletionNotice } from '../utils/accountDeletion';
import { QRCodeSVG } from 'qrcode.react';
import {
  Shield,
  User,
  KeyRound,
  Laptop,
  Smartphone,
  Trash2,
  CheckCircle2,
  AlertTriangle,
  ExternalLink,
  Loader2,
  Layers,
  BookOpen,
  Edit2,
  Calendar,
  Key,
  Camera,
  Terminal,
  Users,
  ShieldCheck,
  Crown,
  Search,
  Utensils,
  Ban,
  LockOpen,
} from 'lucide-react';
import { getErrorMessage } from '../utils/errorUtils';
import { moderationFor, validateSuspensionReason } from '../utils/adminModeration';
import { getBlogUrl, getMenuUrl } from '../utils/urlUtils';
import { UserRole } from '../types/auth';

export const MyAccountPage: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { accounts, activeAccountIndex, updateActiveProfile, removeAccountByEmail } = useAuthStore();
  const activeAccount = accounts[activeAccountIndex] || accounts[0];

  const [activeTab, setActiveTab] = useState<'home' | 'info' | 'security' | 'sessions' | 'apps' | 'admin'>('home');

  useEffect(() => {
    if (location.state?.tab) {
      setActiveTab(location.state.tab);
    }
  }, [location.state]);

  // 프로필 정보 상태
  const [profile, setProfile] = useState<UserProfileData | null>(null);
  const [loadingProfile, setLoadingProfile] = useState(false);

  // 어드민 사용자 관리 상태
  const [adminUsers, setAdminUsers] = useState<UserProfileData[]>([]);
  const [loadingAdminUsers, setLoadingAdminUsers] = useState(false);
  const [adminActionUserId, setAdminActionUserId] = useState<string | null>(null);
  const [adminSuccessMsg, setAdminSuccessMsg] = useState<string | null>(null);
  const [adminErrorMsg, setAdminErrorMsg] = useState<string | null>(null);

  const fetchAdminUsers = async () => {
    setLoadingAdminUsers(true);
    setAdminErrorMsg(null);
    try {
      const data = await authApi.getAdminUsers();
      setAdminUsers(data || []);
    } catch (err: unknown) {
      setAdminErrorMsg(getErrorMessage(err, '사용자 목록을 불러오지 못했습니다.'));
    } finally {
      setLoadingAdminUsers(false);
    }
  };

  const handleSetUserRole = async (targetUserId: string, targetName: string, newRole: UserRole) => {
    if (newRole === 'SUPER_ADMIN') {
      if (!window.confirm(`${targetName || '해당 사용자'}님을 최고 관리자(SUPER_ADMIN)로 승격하시겠습니까?\n최고 관리자는 시스템의 모든 권한을 갖습니다.`)) {
        return;
      }
    }
    setAdminActionUserId(targetUserId);
    setAdminSuccessMsg(null);
    setAdminErrorMsg(null);
    try {
      const updated = await authApi.updateUserRole(targetUserId, newRole);
      setAdminUsers((prev) => prev.map((u) => (u.id === targetUserId ? updated : u)));
      setAdminSuccessMsg(`${targetName || '사용자'}님의 권한 등급이 ${newRole}로 변경되었습니다.`);
    } catch (err: unknown) {
      setAdminErrorMsg(getErrorMessage(err, '권한 변경에 실패했습니다.'));
    } finally {
      setAdminActionUserId(null);
    }
  };

  const [adminResetTotpUserId, setAdminResetTotpUserId] = useState<string | null>(null);

  const handleResetUserTwoFactor = async (targetUserId: string, targetName: string, targetRole: string) => {
    if (!isSuperAdmin && targetRole === 'SUPER_ADMIN') {
      alert('일반 관리자는 최고 관리자의 2FA를 취소할 수 없습니다.');
      return;
    }
    if (!window.confirm(`${targetName || '해당 사용자'}님의 2단계 인증(2FA)을 취소(해제)하시겠습니까?\n해제 후 해당 사용자는 2차 인증 없이 비밀번호만으로 즉시 로그인할 수 있습니다.`)) {
      return;
    }
    setAdminResetTotpUserId(targetUserId);
    setAdminSuccessMsg(null);
    setAdminErrorMsg(null);
    try {
      await authApi.resetUserTwoFactor(targetUserId);
      setAdminUsers((prev) =>
        prev.map((u) => (u.id === targetUserId ? { ...u, hasTotp: false } : u))
      );
      setAdminSuccessMsg(`${targetName || '사용자'}님의 2단계 인증(2FA)이 성공적으로 취소(해제)되었습니다.`);
    } catch (err: unknown) {
      setAdminErrorMsg(getErrorMessage(err, '2단계 인증 취소에 실패했습니다.'));
    } finally {
      setAdminResetTotpUserId(null);
    }
  };

  // 계정 정지·해제·잠금 해제. 서버가 돌려준 갱신된 사용자 정보로 목록의 해당 줄을 바꾼다.
  const [moderatingUserId, setModeratingUserId] = useState<string | null>(null);

  const runModeration = async (
    targetUserId: string,
    action: () => Promise<UserProfileData>,
    successMessage: string,
    failureMessage: string,
  ) => {
    setModeratingUserId(targetUserId);
    setAdminSuccessMsg(null);
    setAdminErrorMsg(null);
    try {
      const updated = await action();
      setAdminUsers((prev) => prev.map((u) => (u.id === targetUserId ? updated : u)));
      setAdminSuccessMsg(successMessage);
    } catch (err: unknown) {
      setAdminErrorMsg(getErrorMessage(err, failureMessage));
    } finally {
      setModeratingUserId(null);
    }
  };

  const handleSuspendUser = async (targetUserId: string, targetName: string) => {
    const name = targetName || '해당 사용자';
    const input = validateSuspensionReason(
      window.prompt(`${name}님의 계정을 정지합니다.\n로그인할 수 없게 되고 모든 기기에서 즉시 로그아웃됩니다.\n\n정지 사유를 입력해 주세요 (200자 이내, 관리자에게만 보입니다):`),
    );
    if (input === null) return;
    if ('error' in input) {
      setAdminSuccessMsg(null);
      setAdminErrorMsg(input.error);
      return;
    }
    await runModeration(
      targetUserId,
      () => authApi.suspendUser(targetUserId, input.reason),
      `${name}님의 계정을 정지했습니다.`,
      '계정 정지에 실패했습니다.',
    );
  };

  const handleReinstateUser = async (targetUserId: string, targetName: string) => {
    const name = targetName || '해당 사용자';
    if (!window.confirm(`${name}님의 정지를 해제하시겠습니까?\n해제하면 다시 로그인할 수 있습니다.`)) return;
    await runModeration(targetUserId, () => authApi.reinstateUser(targetUserId), `${name}님의 정지를 해제했습니다.`, '정지 해제에 실패했습니다.');
  };

  const handleUnlockUser = async (targetUserId: string, targetName: string) => {
    const name = targetName || '해당 사용자';
    await runModeration(targetUserId, () => authApi.unlockUser(targetUserId), `${name}님의 로그인 잠금을 해제했습니다.`, '잠금 해제에 실패했습니다.');
  };

  // 관리자 사용자 검색 및 필터 상태
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedRoleFilter, setSelectedRoleFilter] = useState<'ALL' | 'SUPER_ADMIN' | 'ADMIN' | 'USER' | 'TOTP' | 'SUSPENDED'>('ALL');

  // 프로필 사진 변경 모달 상태
  const [isAvatarModalOpen, setIsAvatarModalOpen] = useState(false);

  // 이름 수정 상태
  const [isEditingName, setIsEditingName] = useState(false);
  const [nameInput, setNameInput] = useState('');
  const [savingName, setSavingName] = useState(false);
  const [profileSuccessMsg, setProfileSuccessMsg] = useState<string | null>(null);
  const [profileErrorMsg, setProfileErrorMsg] = useState<string | null>(null);

  // 비밀번호 변경 상태
  const [isChangingPassword, setIsChangingPassword] = useState(false);
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [savingPassword, setSavingPassword] = useState(false);
  const [passwordSuccessMsg, setPasswordSuccessMsg] = useState<string | null>(null);
  const [passwordErrorMsg, setPasswordErrorMsg] = useState<string | null>(null);

  // 세션 목록 상태
  const [sessions, setSessions] = useState<SessionResponseDto[]>([]);
  const [, setLoadingSessions] = useState(false);
  const [revokingSessionId, setRevokingSessionId] = useState<string | null>(null);
  const [revokingOthers, setRevokingOthers] = useState(false);

  // 2FA 등록 상태
  const [totpSetupData, setTotpSetupData] = useState<TotpSetupData | null>(null);
  const [totpVerifyCode, setTotpVerifyCode] = useState('');
  const [totpSetupPassword, setTotpSetupPassword] = useState('');
  const [totpLoading, setTotpLoading] = useState(false);
  const [totpSuccessMessage, setTotpSuccessMessage] = useState<string | null>(null);
  const [totpErrorMessage, setTotpErrorMessage] = useState<string | null>(null);

  // 2FA 비활성화(해제) 상태
  const [disablingTotp, setDisablingTotp] = useState(false);
  const [showDisableConfirm, setShowDisableConfirm] = useState(false);
  const [disableTotpCode, setDisableTotpCode] = useState('');

  const handleDisable2fa = async () => {
    setDisablingTotp(true);
    setTotpErrorMessage(null);
    setTotpSuccessMessage(null);
    try {
      await authApi.disable2fa(disableTotpCode);
      setTotpSuccessMessage('2단계 인증(2FA)이 성공적으로 해제(비활성화)되었습니다.');
      setShowDisableConfirm(false);
      setDisableTotpCode('');
      setTotpSetupData(null);
      fetchProfile();
    } catch (err: unknown) {
      setTotpErrorMessage(getErrorMessage(err, '2FA 해제에 실패했습니다.'));
    } finally {
      setDisablingTotp(false);
    }
  };

  useEffect(() => {
    if (!activeAccount) {
      navigate('/login');
    }
  }, [activeAccount, navigate]);

  useEffect(() => {
    if (activeAccount) {
      fetchProfile();
    }
  }, [activeAccountIndex]);

  useEffect(() => {
    if (activeTab === 'sessions' || activeTab === 'home') {
      fetchSessions();
    }
    if (activeTab === 'info') {
      fetchProfile();
    }
    if (activeTab === 'admin') {
      fetchAdminUsers();
    }
  }, [activeTab]);

  const fetchProfile = async () => {
    setLoadingProfile(true);
    try {
      const data = await authApi.getProfile();
      setProfile(data);
      setNameInput(data.name || activeAccount?.fullName || '');
      updateActiveProfile(data.name, data.profileImageUrl, data.role as any);
    } catch {
      setNameInput(activeAccount?.fullName || '');
    } finally {
      setLoadingProfile(false);
    }
  };

  const handleUpdateName = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!nameInput.trim()) {
      setProfileErrorMsg('이름을 입력해 주세요.');
      return;
    }

    setSavingName(true);
    setProfileSuccessMsg(null);
    setProfileErrorMsg(null);

    try {
      const updated = await authApi.updateProfile({ name: nameInput.trim() });
      setProfile(updated);
      updateActiveProfile(updated.name, updated.profileImageUrl);
      setProfileSuccessMsg('개인정보(이름)가 성공적으로 수정되었습니다.');
      setIsEditingName(false);
    } catch (err: unknown) {
      setProfileErrorMsg(getErrorMessage(err, '프로필 수정에 실패했습니다.'));
    } finally {
      setSavingName(false);
    }
  };

  // 서버가 탈퇴를 접수하면 모든 세션이 이미 끝났으므로, 이 계정을 기기에서 지우고 로그인 화면에서 안내한다.
  const handleDeletionRequested = (scheduledPurgeAt: string) => {
    if (activeAccount) {
      removeAccountByEmail(activeAccount.email);
    }
    navigate('/login', { replace: true, state: { notice: deletionNotice(scheduledPurgeAt) } });
  };

  const handleChangePassword = async (e: React.FormEvent) => {
    e.preventDefault();
    setPasswordSuccessMsg(null);
    setPasswordErrorMsg(null);

    if (newPassword.length < 8) {
      setPasswordErrorMsg('새 비밀번호는 최소 8자 이상이어야 합니다.');
      return;
    }
    if (newPassword !== confirmPassword) {
      setPasswordErrorMsg('새 비밀번호가 일치하지 않습니다.');
      return;
    }

    setSavingPassword(true);
    try {
      await authApi.changePassword({ currentPassword, newPassword });
      setPasswordSuccessMsg('비밀번호가 성공적으로 변경되었습니다!');
      setCurrentPassword('');
      setNewPassword('');
      setConfirmPassword('');
      setIsChangingPassword(false);
    } catch (err: unknown) {
      setPasswordErrorMsg(getErrorMessage(err, '현재 비밀번호가 올바르지 않거나 변경에 실패했습니다.'));
    } finally {
      setSavingPassword(false);
    }
  };

  const fetchSessions = async () => {
    setLoadingSessions(true);
    try {
      const data = await authApi.getSessions();
      setSessions(data || []);
    } catch {
      setSessions([]);
    } finally {
      setLoadingSessions(false);
    }
  };

  const handleRevokeSession = async (sessionId: string) => {
    setRevokingSessionId(sessionId);
    try {
      await authApi.revokeSession(sessionId);
      setSessions((prev) => prev.filter((s) => s.sessionId !== sessionId));
    } catch {
      setSessions((prev) => prev.filter((s) => s.sessionId !== sessionId));
    } finally {
      setRevokingSessionId(null);
    }
  };

  const handleRevokeOtherSessions = async () => {
    if (!activeAccount?.sessionId) return;
    setRevokingOthers(true);
    try {
      await authApi.revokeOtherSessions(activeAccount.sessionId);
      setSessions((prev) => prev.filter((s) => s.sessionId === activeAccount.sessionId));
    } catch (err: unknown) {
      console.error(err);
    } finally {
      setRevokingOthers(false);
    }
  };

  const handleStart2faSetup = async () => {
    setTotpErrorMessage(null);
    setTotpSuccessMessage(null);
    if (profile?.hasTotp) {
      setTotpErrorMessage('이미 2단계 인증이 활성화되어 있습니다. 해제한 뒤 다시 설정해 주세요.');
      return;
    }
    if (totpSetupPassword.length === 0) {
      setTotpErrorMessage('현재 비밀번호를 입력해 주세요.');
      return;
    }
    setTotpLoading(true);
    try {
      const data = await authApi.setup2fa(totpSetupPassword);
      setTotpSetupData(data);
      setTotpSetupPassword('');
    } catch (err: unknown) {
      setTotpErrorMessage(getErrorMessage(err, '2FA 설정 정보를 불러오지 못했습니다.'));
    } finally {
      setTotpLoading(false);
    }
  };

  const handleConfirm2fa = async (e: React.FormEvent) => {
    e.preventDefault();
    setTotpLoading(true);
    setTotpErrorMessage(null);
    try {
      await authApi.enable2fa(totpVerifyCode);
      setTotpSuccessMessage('2단계 인증(TOTP)이 성공적으로 활성화되었습니다! 이제 로그인 시 2FA가 요구됩니다.');
      setTotpSetupData(null);
      setTotpVerifyCode('');
      fetchProfile();
    } catch (err: unknown) {
      setTotpErrorMessage(getErrorMessage(err, '인증 코드가 올바르지 않습니다.'));
    } finally {
      setTotpLoading(false);
    }
  };

  if (!activeAccount) return null;

  const currentDisplayName = profile?.name || activeAccount.fullName;
  const currentAvatar = profile?.profileImageUrl ?? activeAccount.profileImageUrl;

  const userRole = profile?.role || activeAccount?.role || 'USER';
  const isSuperAdmin = userRole === 'SUPER_ADMIN';
  const isAdmin = userRole === 'ADMIN' || isSuperAdmin;

  return (
    <div className="max-w-7xl mx-auto px-4 sm:px-6 py-8">
      {/* Top Welcome Banner */}
      <div className="mb-8 p-6 sm:p-8 bg-gradient-to-r from-indigo-900 via-indigo-800 to-slate-900 rounded-3xl text-white shadow-xl relative overflow-hidden">
        <div className="absolute right-0 top-0 w-96 h-96 bg-indigo-500/10 rounded-full blur-3xl pointer-events-none"></div>
        <div className="relative z-10 flex flex-col sm:flex-row items-start sm:items-center justify-between gap-6">
          <div className="flex items-center gap-4">
            {/* Avatar with Camera Button */}
            <div className="relative group">
              <div className="w-16 h-16 rounded-2xl overflow-hidden bg-white/10 backdrop-blur-md border border-white/20 text-white flex items-center justify-center text-2xl font-black shadow-inner">
                {currentAvatar ? (
                  <img src={currentAvatar} alt="Profile" className="w-full h-full object-cover" />
                ) : (
                  currentDisplayName ? currentDisplayName.charAt(0).toUpperCase() : 'U'
                )}
              </div>
              <button
                onClick={() => setIsAvatarModalOpen(true)}
                className="absolute -bottom-1 -right-1 p-1.5 bg-indigo-600 hover:bg-indigo-500 rounded-full text-white shadow-md border-2 border-slate-900 transition-transform group-hover:scale-110 cursor-pointer"
                title="프로필 사진 변경"
              >
                <Camera className="w-3.5 h-3.5" />
              </button>
            </div>

            <div>
              <h1 className="text-2xl font-black tracking-tight">{currentDisplayName}님, 환영합니다</h1>
              <p className="text-xs text-indigo-200/80 mt-1">{activeAccount.email} • Doro 중앙 보안 ID</p>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            {/* Role Badge */}
            {isSuperAdmin ? (
              <div className="px-3.5 py-1.5 bg-gradient-to-r from-amber-500/30 to-purple-500/30 border border-amber-400/40 rounded-full text-amber-200 text-xs font-bold flex items-center gap-1.5 shadow-sm">
                <Crown className="w-3.5 h-3.5 text-amber-300" /> 최고 관리자 (SUPER_ADMIN)
              </div>
            ) : isAdmin ? (
              <div className="px-3.5 py-1.5 bg-indigo-500/30 border border-indigo-400/40 rounded-full text-indigo-200 text-xs font-bold flex items-center gap-1.5">
                <ShieldCheck className="w-3.5 h-3.5 text-indigo-300" /> 시스템 관리자 (ADMIN)
              </div>
            ) : (
              <div className="px-3.5 py-1.5 bg-slate-700/50 border border-slate-600 rounded-full text-slate-300 text-xs font-bold flex items-center gap-1.5">
                <User className="w-3.5 h-3.5 text-slate-400" /> 일반 회원 (USER)
              </div>
            )}

            <div className="px-3.5 py-1.5 bg-emerald-500/20 border border-emerald-400/30 rounded-full text-emerald-300 text-xs font-bold flex items-center gap-1.5">
              <CheckCircle2 className="w-3.5 h-3.5" /> 계정 보안 보호 중
            </div>
          </div>
        </div>
      </div>

      {/* Main Grid: Left Tabs + Right Content */}
      <div className="grid grid-cols-1 lg:grid-cols-4 gap-8">
        {/* Left Sidebar Tabs */}
        <div className="lg:col-span-1 space-y-1.5">
          {[
            { id: 'home', label: '홈', icon: Shield },
            { id: 'info', label: '개인 정보', icon: User },
            { id: 'security', label: '보안 & 2FA 설정', icon: KeyRound },
            { id: 'sessions', label: '기기 및 세션 킬스위치', icon: Laptop },
            { id: 'apps', label: '연결된 서브 서비스', icon: Layers },
            ...(isAdmin ? [{ id: 'admin', label: '사용자 및 권한 관리', icon: Users }] : []),
          ].map((tab) => {
            const Icon = tab.icon;
            const isActive = activeTab === tab.id;
            return (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id as any)}
                className={`w-full flex items-center gap-3 px-4 py-3 rounded-2xl text-xs font-bold transition-all cursor-pointer ${
                  isActive
                    ? 'bg-indigo-600 text-white shadow-md shadow-indigo-500/20 scale-[1.02]'
                    : 'text-slate-600 hover:bg-white hover:text-slate-900'
                }`}
              >
                <Icon className={`w-4 h-4 ${isActive ? 'text-white' : 'text-slate-400'}`} />
                {tab.label}
              </button>
            );
          })}
        </div>

        {/* Right Tab Content */}
        <div className="lg:col-span-3">
          {/* TAB 1: HOME */}
          {activeTab === 'home' && (
            <div className="space-y-6">
              <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                {/* Security Card */}
                <div className="glass-card google-card-shadow rounded-3xl p-6">
                  <div className="flex items-center justify-between mb-4">
                    <div className={`w-10 h-10 rounded-xl ${profile?.hasTotp ? 'bg-emerald-50 text-emerald-600' : 'bg-indigo-50 text-indigo-600'} flex items-center justify-center`}>
                      <KeyRound className="w-5 h-5" />
                    </div>
                    <span className={`text-xs font-bold px-2.5 py-1 rounded-full ${profile?.hasTotp ? 'text-emerald-700 bg-emerald-50 border border-emerald-200' : 'text-indigo-600 bg-indigo-50'}`}>
                      {profile?.hasTotp ? '보호 중 (활성)' : '추천'}
                    </span>
                  </div>
                  <h3 className="text-base font-bold text-slate-800">
                    {profile?.hasTotp ? '2단계 인증(2FA) 활성화됨' : '2단계 인증 (2FA) 강화'}
                  </h3>
                  <p className="text-xs text-slate-500 mt-1 leading-relaxed">
                    {profile?.hasTotp
                      ? '일회용 보안 코드(TOTP)로 계정이 철저히 보호되고 있습니다. 보안 설정을 언제든 관리할 수 있습니다.'
                      : 'Google Authenticator 또는 1Password로 로그인 시 OTP 번호를 입력하여 계정을 철저하게 보호하세요.'}
                  </p>
                  <button
                    onClick={() => setActiveTab('security')}
                    className={`mt-4 w-full py-2.5 px-4 text-xs font-bold rounded-xl transition-colors text-center cursor-pointer ${
                      profile?.hasTotp
                        ? 'bg-emerald-50 hover:bg-emerald-100 text-emerald-700 border border-emerald-200'
                        : 'bg-indigo-50 hover:bg-indigo-100 text-indigo-700'
                    }`}
                  >
                    {profile?.hasTotp ? '2FA 보안 상태 및 기기 관리' : '2FA 설정하러 가기'}
                  </button>
                </div>

                {/* Devices Card */}
                <div className="glass-card google-card-shadow rounded-3xl p-6">
                  <div className="flex items-center justify-between mb-4">
                    <div className="w-10 h-10 rounded-xl bg-amber-50 text-amber-600 flex items-center justify-center">
                      <Laptop className="w-5 h-5" />
                    </div>
                    <span className="text-xs font-bold text-slate-500">{sessions.length}개 활성 기기</span>
                  </div>
                  <h3 className="text-base font-bold text-slate-800">내 로그인 기기 관리</h3>
                  <p className="text-xs text-slate-500 mt-1 leading-relaxed">
                    현재 로그인되어 있는 컴퓨터, 스마트폰을 확인하고 분실 시 원격으로 세션을 즉시 종료할 수 있습니다.
                  </p>
                  <button
                    onClick={() => setActiveTab('sessions')}
                    className="mt-4 w-full py-2.5 px-4 bg-slate-100 hover:bg-slate-200 text-slate-700 text-xs font-bold rounded-xl transition-colors text-center cursor-pointer"
                  >
                    기기 목록 및 킬스위치 확인
                  </button>
                </div>
              </div>

              {/* Sub-Services Quick Launch */}
              <div className="glass-card google-card-shadow rounded-3xl p-6">
                <div className="flex items-center justify-between mb-4">
                  <div>
                    <h3 className="text-base font-bold text-slate-800">연동된 Doro 서브 서비스</h3>
                    <p className="text-xs text-slate-500 mt-0.5">Doro 중앙 계정으로 원클릭 SSO 인증된 공식 서비스입니다.</p>
                  </div>
                  <button
                    onClick={() => setActiveTab('apps')}
                    className="text-xs font-semibold text-indigo-600 hover:text-indigo-700 flex items-center gap-1 cursor-pointer"
                  >
                    전체 보기 <ExternalLink className="w-3 h-3" />
                  </button>
                </div>

                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  {[
                    {
                      name: 'DORO.log',
                      icon: BookOpen,
                      color: 'text-emerald-500 bg-emerald-50 border-emerald-100',
                      desc: '개발자를 위한 오픈 기술 블로그 & 엔지니어링 지식 공유',
                      link: getBlogUrl(),
                      badge: '공식 서브 서비스',
                      isExternal: true,
                    },
                    {
                      name: '도로메뉴 (Doro Menu)',
                      icon: Utensils,
                      color: 'text-pink-500 bg-pink-50 border-pink-100',
                      desc: '오늘 뭐 먹지? 위치 기반 메뉴 추천 & 도로롱 뽑기',
                      link: getMenuUrl(),
                      badge: '공식 서브 서비스',
                      isExternal: true,
                    },
                    ...(isAdmin
                      ? [
                          {
                            name: 'Doro Ops Logs',
                            icon: Terminal,
                            color: 'text-indigo-500 bg-indigo-50 border-indigo-100',
                            desc: '전체 마이크로서비스 실시간 분산 추적 및 관제 로그',
                            link: '/logs',
                            badge: '실시간 관제',
                            isExternal: false,
                          },
                        ]
                      : []),
                  ].map((s) => {
                    const Icon = s.icon;
                    return (
                      <div
                        key={s.name}
                        onClick={() => {
                          if (s.isExternal || s.link.startsWith('http') || s.link === '/') {
                            window.location.href = s.link;
                          } else {
                            navigate(s.link);
                          }
                        }}
                        className="p-5 bg-slate-50/70 hover:bg-white border border-slate-200/80 hover:border-emerald-300 rounded-2xl flex flex-col justify-between hover:shadow-md transition-all cursor-pointer group"
                      >
                        <div className="flex items-start justify-between mb-3">
                          <div className={`w-12 h-12 rounded-2xl ${s.color} border flex items-center justify-center shadow-2xs group-hover:scale-105 transition-transform`}>
                            <Icon className="w-6 h-6" />
                          </div>
                          <span
                            className={`text-[10px] font-bold px-2 py-0.5 rounded-full ${
                              s.badge === '실시간 관제'
                                ? 'text-indigo-600 bg-indigo-50 border border-indigo-200'
                                : 'text-emerald-600 bg-emerald-50 border border-emerald-200'
                            }`}
                          >
                            {s.badge}
                          </span>
                        </div>
                        <div>
                          <h4 className="text-sm font-bold text-slate-800 group-hover:text-emerald-600 transition-colors flex items-center gap-1.5">
                            {s.name}
                            <ExternalLink className="w-3.5 h-3.5 text-slate-400 group-hover:text-emerald-500 transition-colors" />
                          </h4>
                          <p className="text-xs text-slate-500 mt-1 leading-relaxed">{s.desc}</p>
                        </div>
                      </div>
                    );
                  })}
                </div>
              </div>
            </div>
          )}

          {/* TAB 2: PERSONAL INFO & EDITING */}
          {activeTab === 'info' && (
            <div className="space-y-6 animate-in fade-in duration-200">
              {/* Profile Card */}
              <div className="glass-card google-card-shadow rounded-3xl p-6 sm:p-8 space-y-6">
                <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
                  <div>
                    <h3 className="text-lg font-bold text-slate-800">기본 개인정보</h3>
                    <p className="text-xs text-slate-500 mt-1">Doro 서비스 전반에서 사용되는 프로필 정보입니다.</p>
                  </div>
                  {loadingProfile && <Loader2 className="w-5 h-5 text-indigo-600 animate-spin" />}
                </div>

                {/* Notifications */}
                {profileSuccessMsg && (
                  <div className="p-3.5 bg-emerald-50 border border-emerald-200 rounded-2xl text-xs text-emerald-800 flex items-center gap-2 animate-in fade-in">
                    <CheckCircle2 className="w-4 h-4 text-emerald-600 shrink-0" />
                    <span>{profileSuccessMsg}</span>
                  </div>
                )}
                {passwordSuccessMsg && (
                  <div className="p-3.5 bg-emerald-50 border border-emerald-200 rounded-2xl text-xs text-emerald-800 flex items-center gap-2 animate-in fade-in">
                    <CheckCircle2 className="w-4 h-4 text-emerald-600 shrink-0" />
                    <span>{passwordSuccessMsg}</span>
                  </div>
                )}
                {profileErrorMsg && (
                  <div className="p-3.5 bg-red-50 border border-red-200 rounded-2xl text-xs text-red-800 flex items-center gap-2 animate-in fade-in">
                    <AlertTriangle className="w-4 h-4 text-red-600 shrink-0" />
                    <span>{profileErrorMsg}</span>
                  </div>
                )}

                <div className="divide-y divide-slate-100 text-xs">
                  {/* 프로필 사진 (Profile Photo) */}
                  <div className="py-4.5 flex flex-col sm:flex-row sm:items-center justify-between gap-4">
                    <div className="sm:w-1/3">
                      <span className="font-bold text-slate-500 block">프로필 사진</span>
                      <span className="text-[11px] text-slate-400">Doro 전반에 표시되는 사진입니다.</span>
                    </div>
                    <div className="sm:w-2/3 flex items-center justify-between gap-4">
                      <div className="flex items-center gap-3.5">
                        <div className="w-14 h-14 rounded-2xl overflow-hidden border-2 border-slate-200 bg-gradient-to-tr from-indigo-600 to-violet-500 text-white flex items-center justify-center text-xl font-bold shadow-xs">
                          {currentAvatar ? (
                            <img src={currentAvatar} alt="Profile" className="w-full h-full object-cover" />
                          ) : (
                            currentDisplayName ? currentDisplayName.charAt(0).toUpperCase() : 'U'
                          )}
                        </div>
                        <div className="text-xs text-slate-500">
                          {currentAvatar ? '맞춤 프로필 사진 적용 중' : '기본 이니셜 아바타 사용 중'}
                        </div>
                      </div>
                      <div className="flex items-center gap-2">
                        <button
                          type="button"
                          onClick={() => setIsAvatarModalOpen(true)}
                          className="px-3.5 py-1.5 rounded-xl border border-slate-200 hover:bg-slate-50 text-indigo-600 font-bold flex items-center gap-1.5 transition-colors cursor-pointer text-xs"
                        >
                          <Camera className="w-3.5 h-3.5" />
                          사진 변경
                        </button>
                      </div>
                    </div>
                  </div>

                  {/* 이름 (Name) 수정 영역 */}
                  <div className="py-4.5 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
                    <span className="font-bold text-slate-500 sm:w-1/3">이름 (Full Name)</span>
                    <div className="sm:w-2/3 flex items-center justify-between gap-4">
                      {!isEditingName ? (
                        <>
                          <span className="font-bold text-slate-800 text-sm">{currentDisplayName}</span>
                          <button
                            onClick={() => {
                              setNameInput(currentDisplayName);
                              setIsEditingName(true);
                              setProfileSuccessMsg(null);
                              setProfileErrorMsg(null);
                            }}
                            className="px-3 py-1.5 rounded-xl border border-slate-200 hover:bg-slate-50 text-indigo-600 font-bold flex items-center gap-1.5 transition-colors cursor-pointer"
                          >
                            <Edit2 className="w-3.5 h-3.5" />
                            수정
                          </button>
                        </>
                      ) : (
                        <form onSubmit={handleUpdateName} className="w-full flex items-center gap-2">
                          <input
                            type="text"
                            required
                            autoFocus
                            value={nameInput}
                            onChange={(e) => setNameInput(e.target.value)}
                            placeholder="이름 입력"
                            className="flex-1 px-3.5 py-1.5 bg-slate-50 border border-indigo-400 rounded-xl text-sm font-bold focus:outline-hidden focus:ring-2 focus:ring-indigo-500/20"
                          />
                          <button
                            type="submit"
                            disabled={savingName}
                            className="px-3.5 py-1.5 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl text-xs font-bold transition-all shadow-sm flex items-center gap-1 cursor-pointer disabled:opacity-50"
                          >
                            {savingName ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : '저장'}
                          </button>
                          <button
                            type="button"
                            onClick={() => setIsEditingName(false)}
                            className="px-3 py-1.5 border border-slate-200 hover:bg-slate-50 rounded-xl text-xs font-bold text-slate-600 cursor-pointer"
                          >
                            취소
                          </button>
                        </form>
                      )}
                    </div>
                  </div>

                  {/* 이메일 (Email) */}
                  <div className="py-4.5 flex flex-col sm:flex-row sm:items-center justify-between gap-2">
                    <span className="font-bold text-slate-500 sm:w-1/3">이메일 주소</span>
                    <div className="sm:w-2/3 flex items-center justify-between">
                      <span className="font-semibold text-slate-800 text-sm">{activeAccount.email}</span>
                      <span className="px-2 py-0.5 bg-emerald-50 text-emerald-700 border border-emerald-200 text-[10px] font-bold rounded-full">
                        인증됨
                      </span>
                    </div>
                  </div>

                  {/* 비밀번호 (Password) 변경 영역 */}
                  <div className="py-4.5 flex flex-col sm:flex-row sm:items-start justify-between gap-3">
                    <span className="font-bold text-slate-500 sm:w-1/3 pt-1">비밀번호</span>
                    <div className="sm:w-2/3">
                      {!isChangingPassword ? (
                        <div className="flex items-center justify-between">
                          <span className="font-mono text-slate-400 text-sm tracking-widest">••••••••••••</span>
                          <button
                            onClick={() => {
                              setIsChangingPassword(true);
                              setPasswordSuccessMsg(null);
                              setPasswordErrorMsg(null);
                            }}
                            className="px-3 py-1.5 rounded-xl border border-slate-200 hover:bg-slate-50 text-indigo-600 font-bold flex items-center gap-1.5 transition-colors cursor-pointer"
                          >
                            <Key className="w-3.5 h-3.5" />
                            비밀번호 변경
                          </button>
                        </div>
                      ) : (
                        <form onSubmit={handleChangePassword} className="space-y-3 p-4 bg-slate-50/80 border border-slate-200 rounded-2xl">
                          {passwordErrorMsg && (
                            <div className="p-2.5 bg-red-50 border border-red-200 rounded-xl text-xs text-red-700">
                              {passwordErrorMsg}
                            </div>
                          )}
                          <div>
                            <label className="block text-[11px] font-bold text-slate-600 mb-1">현재 비밀번호</label>
                            <input
                              type="password"
                              required
                              value={currentPassword}
                              onChange={(e) => setCurrentPassword(e.target.value)}
                              placeholder="••••••••"
                              className="w-full px-3 py-2 bg-white border border-slate-200 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 font-medium"
                            />
                          </div>
                          <div>
                            <label className="block text-[11px] font-bold text-slate-600 mb-1">새 비밀번호 (8자 이상)</label>
                            <input
                              type="password"
                              required
                              value={newPassword}
                              onChange={(e) => setNewPassword(e.target.value)}
                              placeholder="••••••••"
                              className="w-full px-3 py-2 bg-white border border-slate-200 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 font-medium"
                            />
                          </div>
                          <div>
                            <label className="block text-[11px] font-bold text-slate-600 mb-1">새 비밀번호 확인</label>
                            <input
                              type="password"
                              required
                              value={confirmPassword}
                              onChange={(e) => setConfirmPassword(e.target.value)}
                              placeholder="••••••••"
                              className="w-full px-3 py-2 bg-white border border-slate-200 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 font-medium"
                            />
                          </div>
                          <div className="pt-2 flex gap-2">
                            <button
                              type="button"
                              onClick={() => setIsChangingPassword(false)}
                              className="flex-1 py-2 px-3 border border-slate-200 hover:bg-white rounded-xl text-xs font-bold text-slate-600 cursor-pointer"
                            >
                              취소
                            </button>
                            <button
                              type="submit"
                              disabled={savingPassword}
                              className="flex-1 py-2 px-3 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl text-xs font-bold transition-all shadow-sm flex items-center justify-center gap-1.5 cursor-pointer disabled:opacity-50"
                            >
                              {savingPassword ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : '비밀번호 저장'}
                            </button>
                          </div>
                        </form>
                      )}
                    </div>
                  </div>

                  {/* 계정 생성일 */}
                  <div className="py-4.5 flex flex-col sm:flex-row sm:items-center justify-between gap-2">
                    <span className="font-bold text-slate-500 sm:w-1/3">계정 생성 일시</span>
                    <div className="sm:w-2/3 flex items-center gap-1.5 text-slate-600">
                      <Calendar className="w-3.5 h-3.5 text-slate-400" />
                      <span>{profile?.createdAt ? new Date(profile.createdAt).toLocaleString() : '확인 중...'}</span>
                    </div>
                  </div>
                </div>
              </div>

              {/* 2FA 사용 여부를 알아야 올바른 입력칸을 보여 줄 수 있으므로 프로필을 불러온 뒤에만 보여 준다. */}
              {profile && (
                <DeleteAccountSection role={profile.role} hasTotp={profile.hasTotp} onRequested={handleDeletionRequested} />
              )}
            </div>
          )}

          {/* TAB 3: SECURITY & 2FA */}
          {activeTab === 'security' && (
            <div className="space-y-6">
              <div className="glass-card google-card-shadow rounded-3xl p-6 sm:p-8">
                <div className="flex items-start justify-between">
                  <div>
                    <h3 className="text-lg font-bold text-slate-800">2단계 인증 (2FA TOTP)</h3>
                    <p className="text-xs text-slate-500 mt-1">비밀번호 외에 인증기 앱의 6자리 보안 코드로 로그인합니다.</p>
                  </div>
                  <div className="w-10 h-10 rounded-xl bg-indigo-50 text-indigo-600 flex items-center justify-center">
                    <KeyRound className="w-5 h-5" />
                  </div>
                </div>

                {totpSuccessMessage && (
                  <div className="mt-4 p-4 bg-emerald-50 border border-emerald-200 rounded-2xl text-xs text-emerald-800 flex items-center gap-2">
                    <CheckCircle2 className="w-4 h-4 text-emerald-600 shrink-0" />
                    <span>{totpSuccessMessage}</span>
                  </div>
                )}

                {totpErrorMessage && (
                  <div className="mt-4 p-4 bg-red-50 border border-red-200 rounded-2xl text-xs text-red-800 flex items-center gap-2">
                    <AlertTriangle className="w-4 h-4 text-red-600 shrink-0" />
                    <span>{totpErrorMessage}</span>
                  </div>
                )}

                {/* 2FA Status Card: Active or Setup */}
                {profile?.hasTotp && !totpSetupData ? (
                  <div className="mt-6 pt-6 border-t border-slate-100 space-y-4">
                    <div className="p-5 bg-emerald-50/70 border border-emerald-200 rounded-2xl flex flex-col sm:flex-row sm:items-center justify-between gap-4">
                      <div className="flex items-start sm:items-center gap-3.5">
                        <div className="w-11 h-11 rounded-2xl bg-emerald-600 text-white flex items-center justify-center shrink-0 shadow-sm shadow-emerald-600/30">
                          <CheckCircle2 className="w-6 h-6" />
                        </div>
                        <div>
                          <div className="flex items-center gap-2">
                            <h4 className="text-sm font-bold text-slate-900">2단계 인증(TOTP)이 활성화되어 있습니다</h4>
                            <span className="px-2 py-0.5 bg-emerald-100 text-emerald-800 text-[10px] font-bold rounded-full">
                              보호 중
                            </span>
                          </div>
                          <p className="text-xs text-slate-600 mt-0.5">
                            로그인할 때마다 Google Authenticator 또는 1Password의 6자리 인증 코드가 요구됩니다.
                          </p>
                        </div>
                      </div>

                      <div className="flex items-center gap-2 self-end sm:self-auto">
                        <button
                          type="button"
                          onClick={handleStart2faSetup}
                          disabled={totpLoading}
                          className="px-3.5 py-2 bg-white hover:bg-slate-50 border border-slate-200 text-slate-700 text-xs font-bold rounded-xl transition-colors shadow-2xs flex items-center gap-1.5 cursor-pointer"
                        >
                          {totpLoading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <KeyRound className="w-3.5 h-3.5 text-slate-500" />}
                          인증 기기 재등록
                        </button>
                        <button
                          type="button"
                          onClick={() => setShowDisableConfirm(true)}
                          className="px-3.5 py-2 bg-red-50 hover:bg-red-100 text-red-600 text-xs font-bold rounded-xl transition-colors cursor-pointer"
                        >
                          2FA 해제
                        </button>
                      </div>
                    </div>

                    {showDisableConfirm && (
                      <div className="p-4 bg-red-50 border border-red-200 rounded-2xl space-y-3 animate-in fade-in duration-150">
                        <div className="flex items-start gap-2.5">
                          <AlertTriangle className="w-5 h-5 text-red-600 shrink-0 mt-0.5" />
                          <div>
                            <h5 className="text-xs font-bold text-red-900">2단계 인증을 비활성화하시겠습니까?</h5>
                            <p className="text-[11px] text-red-700 mt-0.5 leading-relaxed">
                              해제 시 비밀번호만으로 로그인이 가능해지며 계정의 보안 등급이 낮아집니다.
                            </p>
                          </div>
                        </div>
                        <div>
                          <label htmlFor="disable-totp-code" className="block text-[11px] font-bold text-red-900 mb-1">
                            본인 확인: 인증 앱의 현재 6자리 코드
                          </label>
                          <input
                            id="disable-totp-code"
                            type="text"
                            inputMode="numeric"
                            autoComplete="one-time-code"
                            maxLength={6}
                            value={disableTotpCode}
                            onChange={(e) => setDisableTotpCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
                            placeholder="000000"
                            className="w-full px-3 py-2 border border-red-200 rounded-xl text-sm tracking-[0.4em] text-center font-mono bg-white focus:outline-hidden focus:ring-2 focus:ring-red-300"
                          />
                        </div>
                        <div className="flex items-center gap-2 justify-end">
                          <button
                            type="button"
                            onClick={() => { setShowDisableConfirm(false); setDisableTotpCode(''); }}
                            className="px-3 py-1.5 border border-slate-200 bg-white text-slate-600 text-xs font-semibold rounded-xl cursor-pointer hover:bg-slate-50"
                          >
                            취소
                          </button>
                          <button
                            type="button"
                            onClick={handleDisable2fa}
                            disabled={disablingTotp || disableTotpCode.length !== 6}
                            className="px-3.5 py-1.5 bg-red-600 hover:bg-red-700 text-white text-xs font-bold rounded-xl shadow-xs flex items-center gap-1.5 cursor-pointer disabled:opacity-50"
                          >
                            {disablingTotp ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : '2FA 해제 확정'}
                          </button>
                        </div>
                      </div>
                    )}
                  </div>
                ) : !totpSetupData ? (
                  <div className="mt-6 pt-6 border-t border-slate-100 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                    <div>
                      <div className="text-xs font-bold text-slate-800">Authenticator 2FA 등록</div>
                      <div className="text-[11px] text-slate-400">Google Authenticator, Authy, 1Password 지원</div>
                      <input
                        type="password"
                        value={totpSetupPassword}
                        onChange={(e) => setTotpSetupPassword(e.target.value)}
                        placeholder="현재 비밀번호 확인"
                        autoComplete="current-password"
                        aria-label="2FA 등록을 위한 현재 비밀번호"
                        className="mt-2 w-full sm:w-64 px-3 py-2 bg-white border border-slate-200 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 font-medium"
                      />
                    </div>
                    <button
                      onClick={handleStart2faSetup}
                      disabled={totpLoading}
                      className="py-2.5 px-5 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl text-xs font-bold shadow-md shadow-indigo-500/20 transition-all flex items-center gap-2 cursor-pointer"
                    >
                      {totpLoading ? <Loader2 className="w-4 h-4 animate-spin" /> : '2FA 활성화 설정'}
                    </button>
                  </div>
                ) : (
                  /* 2FA Setup Flow with QR Code */
                  <div className="mt-6 pt-6 border-t border-slate-100 space-y-6 animate-in fade-in duration-200">
                    <div className="p-6 bg-slate-50 border border-slate-200 rounded-2xl flex flex-col sm:flex-row items-center gap-6">
                      <div className="p-3 bg-white rounded-2xl shadow-sm border border-slate-200">
                        <QRCodeSVG value={totpSetupData.qrUri} size={160} />
                      </div>
                      <div className="space-y-2 text-center sm:text-left">
                        <h4 className="text-sm font-bold text-slate-800">1. QR 코드를 스캔하세요</h4>
                        <p className="text-xs text-slate-500">Google Authenticator 앱을 열고 위 QR 코드를 스캔합니다.</p>
                        <div className="pt-2">
                          <span className="text-[11px] text-slate-400 font-bold block">또는 수동 입력 시크릿 키:</span>
                          <code className="text-xs font-mono font-bold bg-white px-2 py-1 rounded border border-slate-200 text-indigo-600 select-all">
                            {totpSetupData.secret}
                          </code>
                        </div>
                      </div>
                    </div>

                    <form onSubmit={handleConfirm2fa} className="space-y-4">
                      <h4 className="text-sm font-bold text-slate-800">2. 앱에 표시된 6자리 번호를 입력하세요</h4>
                      <div className="flex gap-3">
                        <input
                          type="text"
                          maxLength={6}
                          required
                          value={totpVerifyCode}
                          onChange={(e) => setTotpVerifyCode(e.target.value.replace(/\D/g, ''))}
                          placeholder="123456"
                          className="w-48 py-2.5 px-4 text-center tracking-widest text-lg font-mono font-bold bg-slate-50 border border-slate-200 rounded-xl focus:outline-hidden focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 transition-all"
                        />
                        <button
                          type="submit"
                          disabled={totpLoading || totpVerifyCode.length !== 6}
                          className="py-2.5 px-6 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl text-xs font-bold transition-all shadow-md shadow-indigo-500/25 disabled:opacity-50 cursor-pointer"
                        >
                          {totpLoading ? <Loader2 className="w-4 h-4 animate-spin" /> : '활성화 확정'}
                        </button>
                        <button
                          type="button"
                          onClick={() => setTotpSetupData(null)}
                          className="py-2.5 px-4 border border-slate-200 hover:bg-slate-50 rounded-xl text-xs font-bold text-slate-600 cursor-pointer"
                        >
                          취소
                        </button>
                      </div>
                    </form>
                  </div>
                )}
              </div>
            </div>
          )}

          {/* TAB 4: ACTIVE SESSIONS & KILL-SWITCH */}
          {activeTab === 'sessions' && (
            <div className="space-y-6">
              <div className="glass-card google-card-shadow rounded-3xl p-6 sm:p-8">
                <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 mb-6">
                  <div>
                    <h3 className="text-lg font-bold text-slate-800">로그인된 기기 및 활성 세션</h3>
                    <p className="text-xs text-slate-500 mt-1">
                      의심스러운 기기가 있다면 즉시 [원격 로그아웃]을 클릭하여 세션을 종료하세요.
                    </p>
                  </div>
                  <div className="flex items-center gap-2">
                    {sessions.filter((s) => s.sessionId !== activeAccount?.sessionId).length > 0 && (
                      <button
                        onClick={handleRevokeOtherSessions}
                        disabled={revokingOthers}
                        className="text-xs font-bold text-red-600 hover:text-red-700 px-3 py-1.5 bg-red-50 hover:bg-red-100 rounded-xl flex items-center gap-1.5 transition-colors cursor-pointer"
                      >
                        {revokingOthers ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Trash2 className="w-3.5 h-3.5" />}
                        다른 모든 기기에서 로그아웃
                      </button>
                    )}
                    <button
                      onClick={fetchSessions}
                      className="text-xs font-semibold text-indigo-600 hover:text-indigo-700 px-3 py-1.5 bg-indigo-50 rounded-xl cursor-pointer"
                    >
                      새로고침
                    </button>
                  </div>
                </div>

                <div className="space-y-3">
                  {sessions.map((sess) => {
                    const isCurrent = sess.sessionId === activeAccount?.sessionId;
                    return (
                      <div
                        key={sess.sessionId}
                        className="p-4 bg-slate-50/70 border border-slate-200/80 rounded-2xl flex flex-col sm:flex-row sm:items-center justify-between gap-4"
                      >
                        <div className="flex items-start gap-3.5">
                          <div className="w-10 h-10 rounded-xl bg-white border border-slate-200 flex items-center justify-center text-slate-700 shrink-0">
                            {sess.deviceInfo?.includes('Phone') || sess.deviceInfo?.includes('Mobile') ? (
                              <Smartphone className="w-5 h-5" />
                            ) : (
                              <Laptop className="w-5 h-5" />
                            )}
                          </div>
                          <div>
                            <div className="flex items-center gap-2">
                              <span className="text-xs font-bold text-slate-800">
                                {sess.deviceInfo || 'Web Browser'}
                              </span>
                              {isCurrent && (
                                <span className="px-2 py-0.5 bg-emerald-100 text-emerald-700 text-[10px] font-bold rounded-full">
                                  현재 접속 기기
                                </span>
                              )}
                            </div>
                            <div className="text-[11px] text-slate-500 mt-0.5">
                              IP: {sess.ipAddress} • 세션ID: <span className="font-mono">{sess.sessionId.substring(0, 12)}...</span>
                            </div>
                          </div>
                        </div>

                        {!isCurrent && (
                          <button
                            onClick={() => handleRevokeSession(sess.sessionId)}
                            disabled={revokingSessionId === sess.sessionId}
                            className="py-2 px-3.5 bg-red-50 hover:bg-red-100 text-red-600 rounded-xl text-xs font-bold flex items-center justify-center gap-1.5 transition-colors cursor-pointer"
                          >
                            {revokingSessionId === sess.sessionId ? (
                              <Loader2 className="w-3.5 h-3.5 animate-spin" />
                            ) : (
                              <Trash2 className="w-3.5 h-3.5" />
                            )}
                            원격 로그아웃 (킬스위치)
                          </button>
                        )}
                      </div>
                    );
                  })}
                </div>
              </div>
            </div>
          )}

          {/* TAB 5: CONNECTED APPS */}
          {activeTab === 'apps' && (
            <div className="glass-card google-card-shadow rounded-3xl p-6 sm:p-8 space-y-6">
              <div>
                <h3 className="text-lg font-bold text-slate-800">연결된 Doro 서브 서비스</h3>
                <p className="text-xs text-slate-500 mt-1">Doro 중앙 계정으로 원클릭 SSO 인증된 서비스 목록입니다.</p>
              </div>

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                {[
                  {
                    name: 'DORO.log',
                    desc: '개발자를 위한 오픈 기술 블로그 & 엔지니어링 아티클',
                    icon: BookOpen,
                    color: 'text-emerald-500 bg-emerald-50 border-emerald-100',
                    link: getBlogUrl(),
                    badge: 'SSO 연동 완료',
                  },
                  {
                    name: '도로메뉴 (Doro Menu)',
                    desc: '오늘 뭐 먹지 고민 해결! 위치 기반 추천 & 메뉴 정해주는 도로롱',
                    icon: Utensils,
                    color: 'text-pink-500 bg-pink-50 border-pink-100',
                    link: getMenuUrl(),
                    badge: '공식 서브 서비스',
                  },
                ].map((app) => {
                  const Icon = app.icon;
                  return (
                    <div key={app.name} className="p-5 bg-white border border-slate-200/80 rounded-2xl flex items-center justify-between hover:border-emerald-300 hover:shadow-xs transition-all">
                      <div className="flex items-center gap-3.5">
                        <div className={`w-12 h-12 rounded-2xl ${app.color} border flex items-center justify-center shrink-0`}>
                          <Icon className="w-6 h-6" />
                        </div>
                        <div>
                          <div className="flex items-center gap-2">
                            <h4 className="text-sm font-bold text-slate-800">{app.name}</h4>
                            <span className="text-[10px] font-bold px-2 py-0.5 rounded-full text-emerald-600 bg-emerald-50 border border-emerald-200">
                              {app.badge}
                            </span>
                          </div>
                          <p className="text-xs text-slate-500 mt-0.5">{app.desc}</p>
                        </div>
                      </div>
                      <a
                        href={app.link}
                        className="py-2 px-3.5 bg-slate-50 border border-slate-200 hover:bg-emerald-600 hover:border-emerald-600 hover:text-white rounded-xl text-xs font-bold text-slate-700 flex items-center gap-1.5 shadow-2xs transition-all cursor-pointer"
                      >
                        열기 <ExternalLink className="w-3.5 h-3.5" />
                      </a>
                    </div>
                  );
                })}
              </div>
            </div>
          )}

          {/* TAB 6: USER & ROLE MANAGEMENT (ADMIN & SUPER_ADMIN) */}
          {activeTab === 'admin' && isAdmin && (
            <div className="glass-card google-card-shadow rounded-3xl p-6 sm:p-8 space-y-6 animate-in fade-in duration-200">
              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
                <div>
                  <div className="flex items-center gap-2">
                    <h3 className="text-lg font-bold text-slate-800">사용자 및 등급 권한 관리</h3>
                    <span className={`px-2.5 py-0.5 rounded-full text-[11px] font-bold ${
                      isSuperAdmin
                        ? 'bg-amber-500/20 border border-amber-400/40 text-amber-800'
                        : 'bg-indigo-500/20 border border-indigo-400/40 text-indigo-800'
                    }`}>
                      {isSuperAdmin ? '👑 최고 관리자 콘솔' : '🛡️ 시스템 관리자 콘솔'}
                    </span>
                  </div>
                  <p className="text-xs text-slate-500 mt-1">
                    전체 Doro ID 사용자의 전역 신분 등급 및 2단계 인증(2FA) 보안을 통합 관리합니다.
                  </p>
                </div>
                <button
                  onClick={fetchAdminUsers}
                  disabled={loadingAdminUsers}
                  className="text-xs font-semibold text-indigo-600 hover:text-indigo-700 px-3.5 py-2 bg-indigo-50 hover:bg-indigo-100 rounded-xl transition-colors cursor-pointer flex items-center gap-1.5 self-start sm:self-auto"
                >
                  {loadingAdminUsers ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Users className="w-3.5 h-3.5" />}
                  새로고침
                </button>
              </div>

              {adminSuccessMsg && (
                <div className="p-3.5 bg-emerald-50 border border-emerald-200 rounded-2xl text-xs text-emerald-800 flex items-center gap-2 animate-in fade-in">
                  <CheckCircle2 className="w-4 h-4 text-emerald-600 shrink-0" />
                  <span>{adminSuccessMsg}</span>
                </div>
              )}
              {adminErrorMsg && (
                <div className="p-3.5 bg-red-50 border border-red-200 rounded-2xl text-xs text-red-800 flex items-center gap-2 animate-in fade-in">
                  <AlertTriangle className="w-4 h-4 text-red-600 shrink-0" />
                  <span>{adminErrorMsg}</span>
                </div>
              )}

              {/* Search and Quick Filters */}
              <div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-3">
                <div className="relative flex-1">
                  <Search className="w-4 h-4 text-slate-400 absolute left-3.5 top-3" />
                  <input
                    type="text"
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                    placeholder="이름 또는 이메일로 검색..."
                    className="w-full pl-10 pr-14 py-2 bg-slate-50 border border-slate-200 rounded-xl text-xs focus:outline-hidden focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 font-medium"
                  />
                  {searchQuery && (
                    <button
                      onClick={() => setSearchQuery('')}
                      className="absolute right-3 top-2 text-[11px] text-slate-400 hover:text-slate-600 cursor-pointer font-semibold px-1"
                    >
                      초기화
                    </button>
                  )}
                </div>

                <div className="flex items-center gap-1.5 overflow-x-auto pb-1 sm:pb-0">
                  <button
                    onClick={() => setSelectedRoleFilter('ALL')}
                    className={`px-3 py-1.5 rounded-xl text-xs font-bold transition-all cursor-pointer whitespace-nowrap ${
                      selectedRoleFilter === 'ALL'
                        ? 'bg-slate-900 text-white shadow-xs'
                        : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
                    }`}
                  >
                    전체 ({adminUsers.length})
                  </button>
                  <button
                    onClick={() => setSelectedRoleFilter('SUPER_ADMIN')}
                    className={`px-3 py-1.5 rounded-xl text-xs font-bold transition-all cursor-pointer whitespace-nowrap ${
                      selectedRoleFilter === 'SUPER_ADMIN'
                        ? 'bg-purple-600 text-white shadow-xs'
                        : 'bg-purple-50 text-purple-700 hover:bg-purple-100'
                    }`}
                  >
                    👑 최고 ({adminUsers.filter((u) => u.role === 'SUPER_ADMIN').length})
                  </button>
                  <button
                    onClick={() => setSelectedRoleFilter('ADMIN')}
                    className={`px-3 py-1.5 rounded-xl text-xs font-bold transition-all cursor-pointer whitespace-nowrap ${
                      selectedRoleFilter === 'ADMIN'
                        ? 'bg-indigo-600 text-white shadow-xs'
                        : 'bg-indigo-50 text-indigo-700 hover:bg-indigo-100'
                    }`}
                  >
                    🛡️ 관리자 ({adminUsers.filter((u) => u.role === 'ADMIN').length})
                  </button>
                  <button
                    onClick={() => setSelectedRoleFilter('USER')}
                    className={`px-3 py-1.5 rounded-xl text-xs font-bold transition-all cursor-pointer whitespace-nowrap ${
                      selectedRoleFilter === 'USER'
                        ? 'bg-slate-600 text-white shadow-xs'
                        : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
                    }`}
                  >
                    👤 일반 ({adminUsers.filter((u) => u.role === 'USER').length})
                  </button>
                  <button
                    onClick={() => setSelectedRoleFilter('TOTP')}
                    className={`px-3 py-1.5 rounded-xl text-xs font-bold transition-all cursor-pointer whitespace-nowrap ${
                      selectedRoleFilter === 'TOTP'
                        ? 'bg-emerald-600 text-white shadow-xs'
                        : 'bg-emerald-50 text-emerald-700 hover:bg-emerald-100'
                    }`}
                  >
                    🔐 2FA ({adminUsers.filter((u) => u.hasTotp).length})
                  </button>
                  <button
                    onClick={() => setSelectedRoleFilter('SUSPENDED')}
                    className={`px-3 py-1.5 rounded-xl text-xs font-bold transition-all cursor-pointer whitespace-nowrap ${
                      selectedRoleFilter === 'SUSPENDED'
                        ? 'bg-rose-600 text-white shadow-xs'
                        : 'bg-rose-50 text-rose-700 hover:bg-rose-100'
                    }`}
                  >
                    🚫 정지·잠김 ({adminUsers.filter((u) => u.status === 'SUSPENDED' || u.locked === true).length})
                  </button>
                </div>
              </div>

              {/* Users List */}
              <div className="space-y-3">
                {adminUsers.length === 0 && !loadingAdminUsers && (
                  <div className="py-12 text-center text-slate-400 text-xs">
                    등록된 사용자가 없거나 목록을 불러올 수 없습니다.
                  </div>
                )}

                {(() => {
                  const q = searchQuery.trim().toLowerCase();
                  const filtered = adminUsers.filter((u) => {
                    const matches =
                      !q ||
                      (u.name && u.name.toLowerCase().includes(q)) ||
                      u.email.toLowerCase().includes(q);
                    if (!matches) return false;
                    if (selectedRoleFilter === 'SUPER_ADMIN') return u.role === 'SUPER_ADMIN';
                    if (selectedRoleFilter === 'ADMIN') return u.role === 'ADMIN';
                    if (selectedRoleFilter === 'USER') return u.role === 'USER';
                    if (selectedRoleFilter === 'TOTP') return u.hasTotp;
                    if (selectedRoleFilter === 'SUSPENDED') return u.status === 'SUSPENDED' || u.locked === true;
                    return true;
                  });

                  if (filtered.length === 0 && adminUsers.length > 0) {
                    return (
                      <div className="py-12 text-center text-slate-400 text-xs">
                        검색 및 필터 조건과 일치하는 사용자가 없습니다.
                      </div>
                    );
                  }

                  return filtered.map((u) => {
                    const isCurrent = u.id === activeAccount.userId;
                    const isTargetSuper = u.role === 'SUPER_ADMIN';
                    const isTargetAdmin = u.role === 'ADMIN';
                    const moderation = moderationFor({ id: activeAccount.userId, isSuperAdmin }, u);
                    const isModerating = moderatingUserId === u.id;

                    return (
                      <div
                        key={u.id}
                        className="p-4 bg-slate-50/70 border border-slate-200/80 rounded-2xl flex flex-col sm:flex-row sm:items-center justify-between gap-4"
                      >
                        <div className="flex items-center gap-3.5">
                          <div className="w-11 h-11 rounded-2xl overflow-hidden bg-gradient-to-tr from-indigo-600 to-violet-500 text-white flex items-center justify-center font-bold text-base shadow-xs shrink-0">
                            {u.profileImageUrl ? (
                              <img src={u.profileImageUrl} alt={u.name} className="w-full h-full object-cover" />
                            ) : (
                              u.name ? u.name.charAt(0).toUpperCase() : 'U'
                            )}
                          </div>
                          <div>
                            <div className="flex items-center gap-2">
                              <span className="text-xs font-bold text-slate-800">{u.name || '이름 없음'}</span>
                              {isCurrent && (
                                <span className="px-2 py-0.2 bg-slate-200 text-slate-700 text-[10px] font-bold rounded-full">
                                  본인
                                </span>
                              )}

                              {/* Role Badge */}
                              {isTargetSuper ? (
                                <span className="px-2 py-0.5 bg-purple-100 border border-purple-200 text-purple-700 text-[10px] font-bold rounded-full flex items-center gap-1">
                                  <Crown className="w-3 h-3 text-purple-600" /> 최고 관리자
                                </span>
                              ) : isTargetAdmin ? (
                                <span className="px-2 py-0.5 bg-indigo-100 border border-indigo-200 text-indigo-700 text-[10px] font-bold rounded-full flex items-center gap-1">
                                  <ShieldCheck className="w-3 h-3 text-indigo-600" /> 관리자
                                </span>
                              ) : (
                                <span className="px-2 py-0.5 bg-slate-100 text-slate-600 text-[10px] font-bold rounded-full">
                                  일반 회원
                                </span>
                              )}

                              {/* 2FA Status Badge */}
                              {u.hasTotp ? (
                                <span className="px-2 py-0.5 bg-emerald-100 border border-emerald-200 text-emerald-700 text-[10px] font-bold rounded-full flex items-center gap-1">
                                  <KeyRound className="w-2.5 h-2.5 text-emerald-600" /> 2FA 사용 중
                                </span>
                              ) : (
                                <span className="px-2 py-0.5 bg-slate-100 text-slate-400 text-[10px] font-medium rounded-full">
                                  2FA 미사용
                                </span>
                              )}

                              {/* Suspension / Lock Badges */}
                              {u.status === 'SUSPENDED' && (
                                <span className="px-2 py-0.5 bg-rose-100 border border-rose-200 text-rose-700 text-[10px] font-bold rounded-full flex items-center gap-1">
                                  <Ban className="w-2.5 h-2.5" /> 정지됨
                                </span>
                              )}
                              {u.locked && (
                                <span className="px-2 py-0.5 bg-amber-100 border border-amber-200 text-amber-800 text-[10px] font-bold rounded-full">
                                  로그인 잠김
                                </span>
                              )}
                            </div>
                            <div className="text-[11px] text-slate-500 mt-0.5">
                              {u.email} • 가입: {u.createdAt ? new Date(u.createdAt).toLocaleDateString() : '-'}
                            </div>
                            {u.status === 'SUSPENDED' && u.suspensionReason && (
                              <div className="text-[11px] text-rose-700 mt-1 break-words">
                                정지 사유: {u.suspensionReason}
                                {u.suspendedAt ? ` (${new Date(u.suspendedAt).toLocaleDateString()})` : ''}
                              </div>
                            )}
                          </div>
                        </div>

                        {/* Action Buttons */}
                        <div className="flex items-center gap-2 self-end sm:self-auto flex-wrap">
                          {/* 정지 / 정지 해제 / 로그인 잠금 해제 */}
                          {moderation.canSuspend && (
                            <button
                              onClick={() => handleSuspendUser(u.id, u.name)}
                              disabled={isModerating}
                              className="py-1.5 px-3 rounded-xl text-xs font-bold transition-all flex items-center gap-1.5 cursor-pointer bg-rose-50 hover:bg-rose-100 text-rose-700 border border-rose-200 disabled:opacity-50 shadow-2xs"
                              title="로그인을 막고 모든 기기에서 로그아웃시킵니다"
                            >
                              {isModerating ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Ban className="w-3.5 h-3.5" />}
                              정지
                            </button>
                          )}
                          {moderation.canReinstate && (
                            <button
                              onClick={() => handleReinstateUser(u.id, u.name)}
                              disabled={isModerating}
                              className="py-1.5 px-3 rounded-xl text-xs font-bold transition-all flex items-center gap-1.5 cursor-pointer bg-emerald-50 hover:bg-emerald-100 text-emerald-700 border border-emerald-200 disabled:opacity-50 shadow-2xs"
                            >
                              {isModerating ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <ShieldCheck className="w-3.5 h-3.5" />}
                              정지 해제
                            </button>
                          )}
                          {moderation.canUnlock && (
                            <button
                              onClick={() => handleUnlockUser(u.id, u.name)}
                              disabled={isModerating}
                              className="py-1.5 px-3 rounded-xl text-xs font-bold transition-all flex items-center gap-1.5 cursor-pointer bg-amber-50 hover:bg-amber-100 text-amber-800 border border-amber-200 disabled:opacity-50 shadow-2xs"
                              title="비밀번호를 여러 번 틀려 잠긴 계정을 바로 로그인할 수 있게 합니다"
                            >
                              {isModerating ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <LockOpen className="w-3.5 h-3.5" />}
                              잠금 해제
                            </button>
                          )}

                          {/* 2FA Cancel / Disable Button */}
                          {u.hasTotp && (
                            <>
                              {!isSuperAdmin && isTargetSuper ? (
                                <span
                                  className="text-[10px] text-slate-400 font-semibold px-2.5 py-1 bg-slate-100 rounded-xl"
                                  title="일반 관리자는 최고 관리자의 2FA를 취소할 수 없습니다"
                                >
                                  2FA 보호됨
                                </span>
                              ) : (
                                <button
                                  onClick={() => handleResetUserTwoFactor(u.id, u.name, u.role)}
                                  disabled={adminResetTotpUserId === u.id}
                                  className="py-1.5 px-3 rounded-xl text-xs font-bold transition-all flex items-center gap-1.5 cursor-pointer bg-rose-50 hover:bg-rose-100 text-rose-700 border border-rose-200 disabled:opacity-50 shadow-2xs"
                                  title="사용자의 2단계 인증(2FA)을 취소(해제)하여 비밀번호로 즉시 로그인할 수 있게 합니다"
                                >
                                  {adminResetTotpUserId === u.id ? (
                                    <Loader2 className="w-3.5 h-3.5 animate-spin" />
                                  ) : (
                                    <KeyRound className="w-3.5 h-3.5" />
                                  )}
                                  2FA 취소(해제)
                                </button>
                              )}
                            </>
                          )}

                          {/* Role Selection Dropdown (SUPER_ADMIN only) */}
                          {isSuperAdmin && !isCurrent && (
                            <div className="relative">
                              <select
                                value={u.role}
                                onChange={(e) => handleSetUserRole(u.id, u.name, e.target.value as UserRole)}
                                disabled={adminActionUserId === u.id}
                                className="py-1.5 pl-3 pr-7 bg-white border border-slate-200 hover:border-indigo-400 rounded-xl text-xs font-bold text-slate-700 focus:outline-hidden focus:ring-2 focus:ring-indigo-500/20 cursor-pointer disabled:opacity-50 transition-colors shadow-2xs"
                              >
                                <option value="USER">👤 일반 회원 (USER)</option>
                                <option value="ADMIN">🛡️ 관리자 (ADMIN)</option>
                                <option value="SUPER_ADMIN">👑 최고 관리자 (SUPER_ADMIN)</option>
                              </select>
                              {adminActionUserId === u.id && (
                                <Loader2 className="w-3.5 h-3.5 animate-spin absolute right-2 top-2 text-indigo-600 pointer-events-none" />
                              )}
                            </div>
                          )}
                        </div>
                      </div>
                    );
                  });
                })()}
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Avatar Change Modal */}
      <AvatarModal
        isOpen={isAvatarModalOpen}
        onClose={() => setIsAvatarModalOpen(false)}
        currentAvatarUrl={currentAvatar}
        onAvatarUpdated={(newUrl) => {
          setProfile((prev) => (prev ? { ...prev, profileImageUrl: newUrl } : prev));
          setProfileSuccessMsg('프로필 사진이 성공적으로 변경되었습니다.');
        }}
      />
    </div>
  );
};

package com.hunnit_beasts.auth.domain.user.service;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.domain.credential.entity.Credential;
import com.hunnit_beasts.auth.domain.credential.repository.CredentialRepository;
import com.hunnit_beasts.auth.domain.user.dto.ChangePasswordRequest;
import com.hunnit_beasts.auth.domain.user.dto.UpdateProfileRequest;
import com.hunnit_beasts.auth.domain.user.dto.UserProfileResponse;
import com.hunnit_beasts.auth.domain.user.entity.User;
import com.hunnit_beasts.auth.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import com.hunnit_beasts.auth.domain.user.entity.UserRole;
import com.hunnit_beasts.auth.infrastructure.guard.GuardClient;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final CredentialRepository credentialRepository;
    private final PasswordEncoder passwordEncoder;
    private final GuardClient guardClient;
    private final UserRelationSyncService userRelationSyncService;

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));

        boolean hasTotp = credentialRepository.findByUserId(userId)
                .map(c -> c.getTotpSecret() != null && !c.getTotpSecret().isBlank())
                .orElse(false);

        return new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getProfileImageUrl(),
                user.getStatus().name(),
                user.getRole() != null ? user.getRole().name() : "USER",
                hasTotp,
                user.getCreatedAt()
        );
    }

    @Transactional
    public UserProfileResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));

        user.updateProfile(request.name(), request.profileImageUrl());
        log.info("User profile updated: userId={}, newName={}, hasImage={}",
                userId, user.getName(), user.getProfileImageUrl() != null);

        boolean hasTotp = credentialRepository.findByUserId(userId)
                .map(c -> c.getTotpSecret() != null && !c.getTotpSecret().isBlank())
                .orElse(false);

        return new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getProfileImageUrl(),
                user.getStatus().name(),
                user.getRole() != null ? user.getRole().name() : "USER",
                hasTotp,
                user.getCreatedAt()
        );
    }

    @Transactional(readOnly = true)
    public List<UserProfileResponse> getAllUsers() {
        return userRepository.findAll().stream()
                .map(user -> {
                    boolean hasTotp = credentialRepository.findByUserId(user.getId())
                            .map(c -> c.getTotpSecret() != null && !c.getTotpSecret().isBlank())
                            .orElse(false);
                    return new UserProfileResponse(
                            user.getId(),
                            user.getEmail(),
                            user.getName(),
                            user.getProfileImageUrl(),
                            user.getStatus().name(),
                            user.getRole() != null ? user.getRole().name() : "USER",
                            hasTotp,
                            user.getCreatedAt()
                    );
                })
                .toList();
    }

    @Transactional
    public UserProfileResponse changeUserRole(UUID targetUserId, UserRole newRole, UUID adminId) {
        // Doro Guard(Zanzibar ReBAC) 인가 검증: system:doro#manage_roles
        boolean allowed = guardClient.check("system", "doro", "manage_roles", adminId.toString());
        if (!allowed) {
            log.warn("ReBAC Access Denied: adminId={} is not authorized to manage roles", adminId);
            throw new AuthException(ErrorCode.ACCESS_DENIED, "사용자 역할을 변경할 권한이 없습니다.");
        }

        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));

        user.changeRole(newRole);
        userRelationSyncService.syncUserTuples(user, newRole);
        log.info("User role changed and Zanzibar ReBAC tuples synchronized: targetUserId={}, newRole={}, adminId={}",
                targetUserId, newRole, adminId);

        boolean hasTotp = credentialRepository.findByUserId(targetUserId)
                .map(c -> c.getTotpSecret() != null && !c.getTotpSecret().isBlank())
                .orElse(false);

        return new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getProfileImageUrl(),
                user.getStatus().name(),
                user.getRole().name(),
                hasTotp,
                user.getCreatedAt()
        );
    }

    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        Credential credential = credentialRepository.findByUserId(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));

        if (!passwordEncoder.matches(request.currentPassword(), credential.getPasswordHash())) {
            throw new AuthException(ErrorCode.INVALID_CREDENTIALS, "현재 비밀번호가 일치하지 않습니다.");
        }

        String encodedNewPassword = passwordEncoder.encode(request.newPassword());
        credential.updatePassword(encodedNewPassword);
        log.info("User password changed successfully: userId={}", userId);
    }

    @Transactional
    public void resetUserTwoFactor(UUID targetUserId, UUID adminId) {
        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));

        // Doro Guard(Zanzibar ReBAC) 엔진에 권한 판정 전면 위임 (user:{targetId}#can_reset_2fa@user:{adminId})
        boolean allowed = guardClient.check("user", targetUserId.toString(), "can_reset_2fa", adminId.toString());
        if (!allowed) {
            log.warn("ReBAC Access Denied: adminId={} is not authorized to reset 2FA of targetUserId={}", adminId, targetUserId);
            throw new AuthException(ErrorCode.ACCESS_DENIED, "해당 사용자의 2단계 인증(2FA)을 취소할 권한이 없습니다.");
        }

        Credential credential = credentialRepository.findByUserId(targetUserId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));

        credential.updateTotpSecret(null);
        credential.resetFailedAttempts();
        log.info("SECURITY AUDIT: 2FA disabled by Zanzibar ReBAC decision: targetUserId={}, targetEmail={}, adminId={}",
                targetUserId, targetUser.getEmail(), adminId);
    }
}

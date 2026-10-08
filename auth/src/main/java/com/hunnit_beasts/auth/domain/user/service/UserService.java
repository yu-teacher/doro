package com.hunnit_beasts.auth.domain.user.service;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.common.exception.FieldValidationException;
import com.hunnit_beasts.auth.common.log.LogMasking;
import com.hunnit_beasts.auth.domain.credential.entity.Credential;
import com.hunnit_beasts.auth.domain.credential.repository.CredentialRepository;
import com.hunnit_beasts.auth.domain.credential.service.CredentialService;
import com.hunnit_beasts.auth.domain.session.service.SessionRevocationService;
import com.hunnit_beasts.auth.domain.user.dto.ChangePasswordRequest;
import com.hunnit_beasts.auth.domain.user.dto.UpdateProfileRequest;
import com.hunnit_beasts.auth.domain.user.dto.UserProfileResponse;
import com.hunnit_beasts.auth.domain.user.entity.User;
import com.hunnit_beasts.auth.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
    private final CredentialService credentialService;
    private final PasswordEncoder passwordEncoder;
    private final GuardClient guardClient;
    private final UserRelationSyncService userRelationSyncService;
    private final SessionRevocationService sessionRevocationService;

    /** 프로필 이미지(data: URL 포함) 최대 길이. 포털이 보내는 아바타(약 1MB)를 수용하는 기본값. */
    @Value("${doro.iam.profile.image-max-length:1048576}")
    private int profileImageMaxLength;

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

        String image = request.profileImageUrl();
        if (image != null && image.length() > profileImageMaxLength) {
            throw new FieldValidationException("profileImageUrl",
                    "프로필 이미지는 " + profileImageMaxLength + "자 이하여야 합니다.");
        }

        if (!ProfileImageUrl.isAllowed(image)) {
            throw new FieldValidationException("profileImageUrl",
                    "프로필 이미지는 https 주소 또는 이미지 data URL 만 사용할 수 있습니다.");
        }

        user.updateProfile(request.name(), image);
        log.info("User profile updated: userId={}, name={}, hasImage={}",
                userId, LogMasking.maskName(user.getName()), user.getProfileImageUrl() != null);

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
    public List<UserProfileResponse> getAllUsers(UUID adminId) {
        // JWT 역할 클레임은 토큰 수명만큼 낡을 수 있으므로 최종 인가는 Guard 에 위임한다.
        if (adminId == null || !guardClient.check("system", "doro", "admin", adminId.toString())) {
            throw new AuthException(ErrorCode.ACCESS_DENIED);
        }
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

        // 인가(Guard)를 먼저 판정한 뒤에 입력/대상 검증을 하여, 권한 없는 호출자에게 대상 존재 여부를 노출하지 않는다.
        if (adminId.equals(targetUserId)) {
            throw new AuthException(ErrorCode.INVALID_INPUT, "본인의 역할은 변경할 수 없습니다.");
        }

        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));

        user.changeRole(newRole);
        userRelationSyncService.syncUserTuples(user, newRole);
        // 기존 토큰의 역할 클레임이 새 역할과 어긋나므로 대상의 모든 세션을 종료해 다시 로그인하게 한다.
        sessionRevocationService.revokeOtherSessions(targetUserId, null, "ROLE_CHANGED");
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

    /** 비밀번호 변경 후 현재 세션(keepSessionId)을 제외한 모든 세션을 종료한다. keepSessionId 가 null 이면 전부 종료. */
    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request, UUID keepSessionId) {
        Credential credential = credentialRepository.findByUserId(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));

        // 본인 확인은 로그인과 같은 실패 카운트(잠금)를 공유해, 탈취된 토큰으로 현재 비밀번호를 무차별 대입하지 못하게 한다.
        if (credential.isLocked()) {
            throw new AuthException(ErrorCode.ACCOUNT_LOCKED);
        }
        if (!passwordEncoder.matches(request.currentPassword(), credential.getPasswordHash())) {
            credentialService.recordFailedAttempt(userId);
            throw new AuthException(ErrorCode.INVALID_CREDENTIALS, "현재 비밀번호가 일치하지 않습니다.");
        }
        // 같은 트랜잭션에서 이 엔티티를 바로 수정하므로 별도 트랜잭션이 아니라 엔티티로 초기화한다(낡은 값 덮어쓰기 방지).
        credential.resetFailedAttempts();

        String encodedNewPassword = passwordEncoder.encode(request.newPassword());
        credential.updatePassword(encodedNewPassword);
        sessionRevocationService.revokeOtherSessions(userId, keepSessionId, "PASSWORD_CHANGED");
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
        credential.clearPendingTotp();
        credential.resetFailedAttempts();
        // 2FA 가 풀린 계정의 기존 세션(탈취 가능성 포함)을 모두 종료하여 다시 로그인하게 한다.
        sessionRevocationService.revokeOtherSessions(targetUserId, null, "TWO_FACTOR_RESET");
        log.info("SECURITY AUDIT: 2FA disabled by Zanzibar ReBAC decision: targetUserId={}, targetEmail={}, adminId={}",
                targetUserId, LogMasking.maskEmail(targetUser.getEmail()), adminId);
    }
}

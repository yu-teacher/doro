package com.hunnit_beasts.auth.domain.user.service;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.common.log.LogMasking;
import com.hunnit_beasts.auth.domain.credential.entity.Credential;
import com.hunnit_beasts.auth.domain.credential.repository.CredentialRepository;
import com.hunnit_beasts.auth.domain.session.service.SessionRevocationService;
import com.hunnit_beasts.auth.domain.user.dto.UserProfileResponse;
import com.hunnit_beasts.auth.domain.user.entity.User;
import com.hunnit_beasts.auth.domain.user.entity.UserRole;
import com.hunnit_beasts.auth.domain.user.repository.UserRepository;
import com.hunnit_beasts.auth.infrastructure.guard.GuardClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 관리자가 계정을 정지·해제하고, 비밀번호 실패로 잠긴 계정의 잠금을 푸는 기능.
 *
 * <p>정지(SUSPENDED)는 로그인·토큰 갱신·OAuth 발급을 막고 이미 있는 세션을 모두 끝낸다(이미 있는 인증 쪽 검사가 상태를 본다).
 * 글·지도 같은 서비스 데이터는 그대로 두고 로그인만 막는다.
 *
 * <p>인가: 관리자 여부는 Guard 로 판정한다(JWT 역할 클레임은 낡을 수 있다). 대상 역할 규칙은 아래와 같다.
 * <ul>
 *   <li>본인은 정지할 수 없다.</li>
 *   <li>최고 관리자는 정지할 수 없다(먼저 역할을 내려야 한다). 마지막 최고 관리자가 잠겨 시스템이 잠기는 일을 막는다.</li>
 *   <li>관리자(ADMIN)는 최고 관리자(Guard {@code system:doro#manage_roles})만 정지·해제할 수 있다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountSuspensionService {

    public static final int MAX_REASON_LENGTH = 200;
    static final String REVOKE_REASON = "ACCOUNT_SUSPENDED";

    private final UserRepository userRepository;
    private final CredentialRepository credentialRepository;
    private final SessionRevocationService sessionRevocationService;
    private final GuardClient guardClient;

    @Transactional
    public UserProfileResponse suspend(UUID targetId, String rawReason, UUID adminId) {
        requireAdmin(adminId);
        if (adminId.equals(targetId)) {
            throw new AuthException(ErrorCode.INVALID_INPUT, "본인 계정은 정지할 수 없습니다.");
        }
        String reason = normalizeReason(rawReason);
        User target = userRepository.findById(targetId).orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
        requireMayModerate(target, adminId);
        if (target.getRole() == UserRole.SUPER_ADMIN) {
            throw new AuthException(ErrorCode.ACCESS_DENIED, "최고 관리자는 정지할 수 없습니다. 먼저 역할을 변경하세요.");
        }

        if (!target.isSuspended()) {
            if (!target.isActive()) {
                throw new AuthException(ErrorCode.ACCOUNT_STATE_CONFLICT, "탈퇴 처리 중이거나 탈퇴한 계정은 정지할 수 없습니다.");
            }
            target.suspend(Instant.now(), adminId, reason);
            // 이미 발급된 토큰도 바로 쓸 수 없도록 모든 세션을 끝낸다(서비스들은 세션 폐기를 확인한다).
            sessionRevocationService.revokeOtherSessions(targetId, null, REVOKE_REASON);
            log.warn("SECURITY AUDIT: account suspended: targetUserId={}, targetEmail={}, adminId={}, reason={}",
                    targetId, LogMasking.maskEmail(target.getEmail()), adminId, reason);
        }
        return response(target);
    }

    @Transactional
    public UserProfileResponse reinstate(UUID targetId, UUID adminId) {
        requireAdmin(adminId);
        User target = userRepository.findById(targetId).orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
        requireMayModerate(target, adminId);

        if (target.isSuspended()) {
            target.reinstate();
            credentialRepository.findByUserId(targetId).ifPresent(Credential::resetFailedAttempts);
            log.warn("SECURITY AUDIT: account reinstated: targetUserId={}, targetEmail={}, adminId={}",
                    targetId, LogMasking.maskEmail(target.getEmail()), adminId);
        } else if (!target.isActive()) {
            throw new AuthException(ErrorCode.ACCOUNT_STATE_CONFLICT, "정지 중인 계정이 아닙니다.");
        }
        return response(target);
    }

    /** 비밀번호를 연속으로 틀려 잠긴 계정의 잠금을 푼다. 정지와는 별개이며 세션은 건드리지 않는다. */
    @Transactional
    public UserProfileResponse unlock(UUID targetId, UUID adminId) {
        requireAdmin(adminId);
        User target = userRepository.findById(targetId).orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
        requireMayModerate(target, adminId);

        Credential credential = credentialRepository.findByUserId(targetId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
        credential.resetFailedAttempts();
        log.warn("SECURITY AUDIT: account lock cleared: targetUserId={}, targetEmail={}, adminId={}",
                targetId, LogMasking.maskEmail(target.getEmail()), adminId);
        return response(target);
    }

    /** JWT 역할 클레임이 아니라 Guard 판정으로 관리자 여부를 확인한다. 대상 존재 여부는 이 확인 뒤에야 드러낸다. */
    private void requireAdmin(UUID adminId) {
        if (adminId == null || !guardClient.check("system", "doro", "admin", adminId.toString())) {
            throw new AuthException(ErrorCode.ACCESS_DENIED, "사용자를 관리할 권한이 없습니다.");
        }
    }

    /** 관리자(ADMIN)·최고 관리자 대상 조치는 최고 관리자만 할 수 있다. */
    private void requireMayModerate(User target, UUID adminId) {
        boolean privilegedTarget = target.getRole() == UserRole.ADMIN || target.getRole() == UserRole.SUPER_ADMIN;
        if (privilegedTarget && !guardClient.check("system", "doro", "manage_roles", adminId.toString())) {
            log.warn("Rejected moderation of a privileged account by a non-super admin: adminId={}, targetUserId={}", adminId, target.getId());
            throw new AuthException(ErrorCode.ACCESS_DENIED, "관리자 계정은 최고 관리자만 처리할 수 있습니다.");
        }
    }

    private static String normalizeReason(String raw) {
        String reason = raw == null ? "" : raw.trim();
        if (reason.isEmpty()) {
            throw new AuthException(ErrorCode.INVALID_INPUT, "정지 사유를 입력해 주세요.");
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new AuthException(ErrorCode.INVALID_INPUT, "정지 사유는 " + MAX_REASON_LENGTH + "자 이내로 입력해 주세요.");
        }
        return reason;
    }

    private UserProfileResponse response(User user) {
        var credential = credentialRepository.findByUserId(user.getId());
        boolean hasTotp = credential.map(c -> c.getTotpSecret() != null && !c.getTotpSecret().isBlank()).orElse(false);
        boolean locked = credential.map(c -> c.getLockedUntil() != null && Instant.now().isBefore(c.getLockedUntil())).orElse(false);
        return UserProfileResponse.forAdmin(user, hasTotp, locked);
    }
}

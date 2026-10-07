package com.hunnit_beasts.auth.domain.user.service;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.core.totp.TotpService;
import com.hunnit_beasts.auth.domain.credential.entity.Credential;
import com.hunnit_beasts.auth.domain.credential.repository.CredentialRepository;
import com.hunnit_beasts.auth.domain.credential.service.CredentialService;
import com.hunnit_beasts.auth.domain.session.service.SessionRevocationService;
import com.hunnit_beasts.auth.domain.user.dto.DeleteAccountRequest;
import com.hunnit_beasts.auth.domain.user.dto.DeleteAccountResponse;
import com.hunnit_beasts.auth.domain.user.entity.User;
import com.hunnit_beasts.auth.domain.user.entity.UserRole;
import com.hunnit_beasts.auth.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 회원탈퇴 요청. 즉시 계정을 PENDING_DELETION 으로 바꾸고 모든 세션을 끊는다. 개인정보는 유예 기간이 지난 뒤
 * {@link AccountPurgeService} 가 익명화한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountDeletionService {

    private final UserRepository userRepository;
    private final CredentialRepository credentialRepository;
    private final CredentialService credentialService;
    private final PasswordEncoder passwordEncoder;
    private final TotpService totpService;
    private final SessionRevocationService sessionRevocationService;

    @Value("${doro.iam.account-deletion.grace-days:30}")
    private long graceDays;

    @Transactional
    public DeleteAccountResponse requestDeletion(UUID userId, DeleteAccountRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
        if (!user.isActive()) {
            throw new AuthException(ErrorCode.INVALID_INPUT, "탈퇴를 요청할 수 없는 계정 상태입니다.");
        }

        Credential credential = credentialRepository.findByUserId(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
        if (credential.isLocked()) {
            throw new AuthException(ErrorCode.ACCOUNT_LOCKED);
        }

        // 본인 확인은 로그인과 같은 실패 카운트(잠금)를 공유해, 탈취된 토큰으로 비밀번호를 무차별 대입하지 못하게 한다.
        if (!passwordEncoder.matches(request.password(), credential.getPasswordHash())) {
            credentialService.recordFailedAttempt(userId);
            throw new AuthException(ErrorCode.INVALID_CREDENTIALS, "비밀번호가 일치하지 않습니다.");
        }
        if (credential.hasActiveTotp()) {
            String code = request.totpCode();
            if (code == null || code.isBlank()
                    || !totpService.verifyAndConsume(userId, credential.getTotpSecret(), code)) {
                credentialService.recordFailedAttempt(userId);
                throw new AuthException(ErrorCode.INVALID_2FA_CODE);
            }
        }

        // 플랫폼 관리자가 사라지면 복구할 방법이 없으므로, 역할을 먼저 일반 사용자로 내린 뒤에만 탈퇴할 수 있다.
        if (user.getRole() != UserRole.USER) {
            throw new AuthException(ErrorCode.INVALID_INPUT,
                    "관리자 계정은 역할을 일반 사용자로 변경한 뒤에 탈퇴할 수 있습니다.");
        }

        credentialService.resetFailedAttempts(userId);
        Instant now = Instant.now();
        user.requestDeletion(now);
        // 계정 상태가 바뀌면 토큰 폐기 확인(assertSessionLive)이 이미 거부하지만, 세션·리프레시 토큰·OAuth 세션도 모두 닫는다.
        sessionRevocationService.revokeOtherSessions(userId, null, "ACCOUNT_DELETION_REQUESTED");

        Instant purgeAt = now.plus(Duration.ofDays(graceDays));
        log.info("Account deletion requested: userId={}, scheduledPurgeAt={}", userId, purgeAt);
        return new DeleteAccountResponse(purgeAt);
    }
}

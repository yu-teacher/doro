package com.hunnit_beasts.auth;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.core.totp.TotpService;
import com.hunnit_beasts.auth.domain.auth.dto.LoginRequest;
import com.hunnit_beasts.auth.domain.auth.dto.SignUpRequest;
import com.hunnit_beasts.auth.domain.auth.service.AuthService;
import com.hunnit_beasts.auth.domain.credential.entity.Credential;
import com.hunnit_beasts.auth.domain.credential.repository.CredentialRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class TwoFactorEnrollmentTest {

    private static final String PASSWORD = "Password123!";

    @Autowired
    private AuthService authService;
    @Autowired
    private CredentialRepository credentialRepository;
    @Autowired
    private TotpService totpService;

    private UUID newUser(String email) {
        return authService.signup(new SignUpRequest(email, PASSWORD, "Two Factor"));
    }

    private static String uniqueEmail() {
        return "2fa-" + UUID.randomUUID() + "@doro.local";
    }

    private Credential credential(UUID userId) {
        return credentialRepository.findByUserId(userId).orElseThrow();
    }

    private String code(String secret, long stepOffset) {
        byte[] key = ReflectionTestUtils.invokeMethod(totpService, "decodeBase32", secret);
        int value = ReflectionTestUtils.invokeMethod(totpService, "generateCodeForStep", key,
                Instant.now().getEpochSecond() / 30 + stepOffset);
        return String.format("%06d", value);
    }

    private boolean loginRequires2fa(String email) {
        return authService.login(new LoginRequest(email, PASSWORD, "test"), "127.0.0.1", "UA-" + UUID.randomUUID()).requires2fa();
    }

    @Test
    @DisplayName("setup 만 호출하고 이탈해도 2FA 가 활성화되지 않아 로그인이 잠기지 않는다")
    void setupAloneDoesNotLockTheAccount() {
        String email = uniqueEmail();
        UUID userId = newUser(email);

        authService.setupTotp(userId);

        assertThat(credential(userId).hasActiveTotp()).isFalse();
        assertThat(credential(userId).hasPendingTotp()).isTrue();
        assertThat(loginRequires2fa(email)).isFalse();
    }

    @Test
    @DisplayName("틀린 코드는 2FA 를 활성화하지 않고, 맞는 코드를 확인해야 활성화된다")
    void enrollmentRequiresValidCode() {
        String email = uniqueEmail();
        UUID userId = newUser(email);
        authService.setupTotp(userId);

        assertThatThrownBy(() -> authService.verifyTotp(userId, "000000"))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_2FA_CODE));
        assertThat(credential(userId).hasActiveTotp()).isFalse();

        authService.verifyTotp(userId, code(credential(userId).getPendingTotpSecret(), 0));

        assertThat(credential(userId).hasActiveTotp()).isTrue();
        assertThat(credential(userId).hasPendingTotp()).isFalse();
        assertThat(loginRequires2fa(email)).isTrue();
    }

    @Test
    @DisplayName("이미 2FA 가 활성화된 계정에서 setup 을 다시 호출하면 거부된다 (기존 시크릿을 덮어쓰지 못한다)")
    void setupIsRejectedWhenAlreadyActive() {
        UUID userId = newUser(uniqueEmail());
        authService.setupTotp(userId);
        authService.verifyTotp(userId, code(credential(userId).getPendingTotpSecret(), 0));

        assertThatThrownBy(() -> authService.setupTotp(userId))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    @DisplayName("2FA 해제는 현재 OTP 코드로 재인증해야 하며, 틀린 코드로는 해제되지 않는다")
    void disableRequiresValidCode() {
        String email = uniqueEmail();
        UUID userId = newUser(email);
        authService.setupTotp(userId);
        String secret = credential(userId).getPendingTotpSecret();
        authService.verifyTotp(userId, code(secret, 0));

        assertThatThrownBy(() -> authService.disableTotp(userId, "000000"))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_2FA_CODE));
        assertThat(credential(userId).hasActiveTotp()).isTrue();

        // 등록에 쓴 코드는 재사용할 수 없으므로 다음 스텝의 코드로 해제한다
        authService.disableTotp(userId, code(secret, 1));

        assertThat(credential(userId).hasActiveTotp()).isFalse();
        assertThat(loginRequires2fa(email)).isFalse();
    }
}

package com.hunnit_beasts.auth;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.core.crypto.CustomArgon2PasswordEncoder;
import com.hunnit_beasts.auth.domain.auth.dto.LoginRequest;
import com.hunnit_beasts.auth.domain.auth.dto.LoginResponse;
import com.hunnit_beasts.auth.domain.auth.dto.SignUpRequest;
import com.hunnit_beasts.auth.domain.auth.dto.TotpLoginRequest;
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
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AuthSecurityEdgeCaseTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private CredentialRepository credentialRepository;

    @Autowired
    private CustomArgon2PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("엣지케이스: 비밀번호 5회 연속 실패 시 계정 자동 잠금(15분) 및 올바른 비밀번호도 차단")
    void testAccountLockoutAfter5FailedAttempts() {
        String email = "lockout.test@doro.local";
        UUID userId = authService.signup(new SignUpRequest(email, "CorrectPass123!", "Lockout User"));

        LoginRequest wrongLogin = new LoginRequest(email, "WrongPass!", "Mac");
        LoginRequest correctLogin = new LoginRequest(email, "CorrectPass123!", "Mac");

        // 1~4회 실패: INVALID_CREDENTIALS
        for (int i = 1; i <= 4; i++) {
            assertThatThrownBy(() -> authService.login(wrongLogin, "127.0.0.1", "UA"))
                    .isInstanceOf(AuthException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_CREDENTIALS);
        }

        // 5회째 실패: 잠금 트리거
        assertThatThrownBy(() -> authService.login(wrongLogin, "127.0.0.1", "UA"))
                .isInstanceOf(AuthException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_CREDENTIALS);

        // 6회째: 올바른 비밀번호를 입력해도 ACCOUNT_LOCKED (403) 로 차단
        assertThatThrownBy(() -> authService.login(correctLogin, "127.0.0.1", "UA"))
                .isInstanceOf(AuthException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCOUNT_LOCKED);

        // 15분 경과 시뮬레이션 (lockedUntil을 과거로 설정)
        Credential credential = credentialRepository.findByUserId(userId).orElseThrow();
        ReflectionTestUtils.setField(credential, "lockedUntil", Instant.now().minus(1, ChronoUnit.MINUTES));
        credentialRepository.save(credential);

        // 15분 후 올바른 비밀번호로 로그인 성공 및 실패 카운트 리셋 검증
        LoginResponse response = authService.login(correctLogin, "127.0.0.1", "UA");
        assertThat(response.requires2fa()).isFalse();
        assertThat(response.tokens().accessToken()).isNotBlank();

        Credential updatedCredential = credentialRepository.findByUserId(userId).orElseThrow();
        assertThat(updatedCredential.getFailedAttempts()).isEqualTo(0);
        assertThat(updatedCredential.getLockedUntil()).isNull();
    }

    @Test
    @DisplayName("엣지케이스: 동일 이메일 중복 회원가입 시도 시 EMAIL_ALREADY_EXISTS 예외 발생")
    void testDuplicateEmailSignup() {
        String email = "duplicate.test@doro.local";
        authService.signup(new SignUpRequest(email, "Password123!", "First User"));

        assertThatThrownBy(() -> authService.signup(new SignUpRequest(email, "Password999!", "Second User")))
                .isInstanceOf(AuthException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMAIL_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("엣지케이스: 2FA 활성 계정의 1회용 임시 티켓(tempTicket) 재사용 공격 방어")
    void testTwoFactorTempTicketReplayAttack() {
        String email = "totp.edge@doro.local";
        UUID userId = authService.signup(new SignUpRequest(email, "Password123!", "Totp Edge User"));

        // 2FA 설정 및 활성화
        authService.setupTotp(userId);

        // 1단계 비밀번호 검증 -> tempTicket 발급 확인
        LoginResponse loginResponse = authService.login(new LoginRequest(email, "Password123!", "Mac"), "127.0.0.1", "UA");
        assertThat(loginResponse.requires2fa()).isTrue();
        String tempTicket = loginResponse.tempTicket();
        assertThat(tempTicket).isNotBlank();

        // 잘못된 6자리 코드로 2차 로그인 시도 -> INVALID_2FA_CODE 예외 (최대 5회 허용)
        TotpLoginRequest wrongTotpReq = new TotpLoginRequest(tempTicket, "000000", "Mac");
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> authService.loginWithTotp(wrongTotpReq, "127.0.0.1", "UA"))
                    .isInstanceOf(AuthException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_2FA_CODE);
        }

        // 5회 실패 후 소멸된 tempTicket으로 다시 시도 시 티켓 유효하지 않음(INVALID_TOKEN)으로 차단
        assertThatThrownBy(() -> authService.loginWithTotp(wrongTotpReq, "127.0.0.1", "UA"))
                .isInstanceOf(AuthException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_TOKEN);
    }
}

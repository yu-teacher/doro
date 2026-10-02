package com.hunnit_beasts.auth;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.core.totp.TotpService;
import com.hunnit_beasts.auth.domain.auth.dto.LoginRequest;
import com.hunnit_beasts.auth.domain.auth.dto.LoginResponse;
import com.hunnit_beasts.auth.domain.auth.dto.SignUpRequest;
import com.hunnit_beasts.auth.domain.auth.dto.TotpLoginRequest;
import com.hunnit_beasts.auth.domain.auth.service.AuthService;
import com.hunnit_beasts.auth.domain.credential.entity.Credential;
import com.hunnit_beasts.auth.domain.credential.repository.CredentialRepository;
import com.hunnit_beasts.auth.domain.oauth.service.OAuth2Service;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A4: 메모리 상태(2FA 티켓, OAuth 인가 코드)의 만료 정리와 상한. 상한은 2 로 낮춘 컨텍스트에서 검증한다. */
@SpringBootTest(properties = {
        "doro.iam.two-factor.max-pending-tickets=2",
        "doro.oauth.max-pending-authorization-codes=2"
})
@ActiveProfiles("test")
class InMemoryStateHygieneTest {

    private static final String PASSWORD = "Password123!";
    private static final String REDIRECT_URI = "http://localhost:3000/callback";

    @Autowired
    private AuthService authService;
    @Autowired
    private OAuth2Service oAuth2Service;
    @Autowired
    private CredentialRepository credentialRepository;
    @Autowired
    private TotpService totpService;

    private String newTwoFactorUser() {
        String email = "hygiene-" + UUID.randomUUID() + "@doro.local";
        UUID userId = authService.signup(new SignUpRequest(email, PASSWORD, "Hygiene"));
        Credential credential = credentialRepository.findByUserId(userId).orElseThrow();
        credential.updateTotpSecret(totpService.generateSecret());
        credentialRepository.save(credential);
        return email;
    }

    private LoginResponse loginStep1(String email) {
        return authService.login(new LoginRequest(email, PASSWORD, null), "127.0.0.1", "UA-" + UUID.randomUUID());
    }

    @Test
    @DisplayName("A4: 만료된 2FA 티켓은 스위퍼가 제거하고, 만료되지 않은 티켓은 남긴다")
    void ticketSweeperPurgesOnlyExpired() {
        String email = newTwoFactorUser();
        int base = authService.pendingTwoFactorTicketCount();
        LoginResponse r = loginStep1(email);
        assertThat(r.requires2fa()).isTrue();
        assertThat(authService.pendingTwoFactorTicketCount()).isEqualTo(base + 1);

        assertThat(authService.purgeExpiredTwoFactorTickets(Instant.now())).isZero();
        assertThat(authService.pendingTwoFactorTicketCount()).isEqualTo(base + 1);

        assertThat(authService.purgeExpiredTwoFactorTickets(Instant.now().plusSeconds(301))).isGreaterThanOrEqualTo(1);
        assertThat(authService.pendingTwoFactorTicketCount()).isZero();
        assertThatThrownBy(() -> authService.loginWithTotp(new TotpLoginRequest(r.tempTicket(), "000000", null), "127.0.0.1", "ua"))
                .isInstanceOf(AuthException.class);
    }

    @Test
    @DisplayName("A4: 2FA 티켓이 상한에 도달하면 신규 발급은 TOO_MANY_REQUESTS 로 거부되고, 만료분 정리 후에는 다시 발급된다")
    void ticketCapRejectsNewIssuance() {
        authService.purgeExpiredTwoFactorTickets(Instant.now().plusSeconds(301));
        String email = newTwoFactorUser();
        loginStep1(email);
        loginStep1(email);

        assertThatThrownBy(() -> loginStep1(email))
                .isInstanceOfSatisfying(AuthException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS));
        assertThat(authService.pendingTwoFactorTicketCount()).isEqualTo(2);

        authService.purgeExpiredTwoFactorTickets(Instant.now().plusSeconds(301));
        assertThat(loginStep1(email).requires2fa()).isTrue();
    }

    @Test
    @DisplayName("A4: 만료된 OAuth 인가 코드는 스위퍼가 제거한다")
    void authCodeSweeperPurgesOnlyExpired() {
        oAuth2Service.purgeExpiredAuthorizationCodes(Instant.now().plusSeconds(301));
        oAuth2Service.generateAuthorizationCode("client", REDIRECT_URI, UUID.randomUUID(), "challenge");
        assertThat(oAuth2Service.pendingAuthorizationCodeCount()).isEqualTo(1);

        assertThat(oAuth2Service.purgeExpiredAuthorizationCodes(Instant.now())).isZero();
        assertThat(oAuth2Service.purgeExpiredAuthorizationCodes(Instant.now().plusSeconds(301))).isEqualTo(1);
        assertThat(oAuth2Service.pendingAuthorizationCodeCount()).isZero();
    }

    @Test
    @DisplayName("A4: OAuth 인가 코드가 상한에 도달하면 신규 발급은 TOO_MANY_REQUESTS 로 거부된다")
    void authCodeCapRejectsNewIssuance() {
        oAuth2Service.purgeExpiredAuthorizationCodes(Instant.now().plusSeconds(301));
        oAuth2Service.generateAuthorizationCode("client", REDIRECT_URI, UUID.randomUUID(), "c1");
        oAuth2Service.generateAuthorizationCode("client", REDIRECT_URI, UUID.randomUUID(), "c2");

        assertThatThrownBy(() -> oAuth2Service.generateAuthorizationCode("client", REDIRECT_URI, UUID.randomUUID(), "c3"))
                .isInstanceOfSatisfying(AuthException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS));

        oAuth2Service.purgeExpiredAuthorizationCodes(Instant.now().plusSeconds(301));
        assertThat(oAuth2Service.generateAuthorizationCode("client", REDIRECT_URI, UUID.randomUUID(), "c4")).isNotBlank();
    }
}

package com.hunnit_beasts.auth;

import com.hunnit_beasts.auth.domain.auth.dto.LoginRequest;
import com.hunnit_beasts.auth.domain.auth.dto.SignUpRequest;
import com.hunnit_beasts.auth.domain.auth.service.AuthService;
import com.hunnit_beasts.auth.domain.session.service.SessionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A3a: max-active-sessions-per-user <= 0 은 무제한이다. */
@SpringBootTest(properties = "doro.iam.session.max-active-sessions-per-user=0")
@ActiveProfiles("test")
class SessionLimitUnlimitedTest {

    @Autowired
    private AuthService authService;
    @Autowired
    private SessionService sessionService;

    @Test
    @DisplayName("A3a: 상한이 0 이면 세션 수를 제한하지 않는다")
    void zeroMeansUnlimited() {
        String email = "unlimited-" + UUID.randomUUID() + "@doro.local";
        UUID userId = authService.signup(new SignUpRequest(email, "Password123!", "Unlimited"));
        for (int i = 0; i < 14; i++) {
            authService.login(new LoginRequest(email, "Password123!", null), "10.9.0." + i, "ua-" + i);
        }
        assertThat(sessionService.getActiveSessions(userId)).hasSize(14);
    }
}

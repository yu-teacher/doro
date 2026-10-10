package com.hunnit_beasts.auth;

import com.hunnit_beasts.auth.domain.auth.dto.LoginRequest;
import com.hunnit_beasts.auth.domain.auth.dto.RefreshTokenRequest;
import com.hunnit_beasts.auth.domain.auth.dto.SignUpRequest;
import com.hunnit_beasts.auth.domain.auth.dto.TokenResponse;
import com.hunnit_beasts.auth.domain.auth.service.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 절대 수명을 0 으로 끄면(무활동 만료만 적용) 오래된 세션도 계속 갱신된다. */
@SpringBootTest(properties = "doro.iam.session.absolute-lifetime-seconds=0")
@ActiveProfiles("test")
class SessionAbsoluteLifetimeDisabledTest {

    @Autowired private AuthService authService;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("0 으로 끄면 아주 오래된 세션도 갱신된다")
    void disabledAllowsOldSessions() {
        String email = "abs-off-" + UUID.randomUUID() + "@doro.local";
        authService.signup(new SignUpRequest(email, "Password123!", "Abs Off"));
        TokenResponse tokens = authService.login(new LoginRequest(email, "Password123!", "test"), "127.0.0.1", "UA-" + UUID.randomUUID()).tokens();
        jdbc.update("update user_sessions set created_at = ? where id = ?", Timestamp.from(Instant.now().minus(Duration.ofDays(400))), tokens.sessionId());

        assertThat(authService.refresh(new RefreshTokenRequest(tokens.refreshToken())).accessToken()).isNotBlank();
    }
}

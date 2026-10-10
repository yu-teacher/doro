package com.hunnit_beasts.auth;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.core.redis.KillSwitchPublisher;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/** 세션 절대 수명(기본 90일): 활동이 있어도 만들어진 지 너무 오래된 세션은 갱신되지 않고, 새 리프레시 토큰도 그 끝을 넘지 못한다. */
@SpringBootTest
@ActiveProfiles("test")
class SessionAbsoluteLifetimeTest {

    private static final String PASSWORD = "Password123!";
    private static final long ABSOLUTE_DAYS = 90;

    @Autowired private AuthService authService;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private KillSwitchPublisher killSwitch;

    private String email;

    private TokenResponse newSession() {
        email = "abs-" + UUID.randomUUID() + "@doro.local";
        authService.signup(new SignUpRequest(email, PASSWORD, "Abs Life"));
        return authService.login(new LoginRequest(email, PASSWORD, "test"), "127.0.0.1", "UA-" + UUID.randomUUID()).tokens();
    }

    private void backdateSession(UUID sessionId, Duration age) {
        jdbc.update("update user_sessions set created_at = ? where id = ?", Timestamp.from(Instant.now().minus(age)), sessionId);
    }

    private boolean sessionActive(UUID sessionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select is_active from user_sessions where id = ?", Boolean.class, sessionId));
    }

    @Test
    @DisplayName("절대 수명 안의 세션은 계속 갱신된다")
    void sessionWithinLifetimeKeepsRefreshing() {
        TokenResponse tokens = newSession();
        backdateSession(tokens.sessionId(), Duration.ofDays(ABSOLUTE_DAYS - 10));

        TokenResponse refreshed = authService.refresh(new RefreshTokenRequest(tokens.refreshToken()));

        assertThat(refreshed.accessToken()).isNotBlank();
        assertThat(sessionActive(tokens.sessionId())).isTrue();
    }

    @Test
    @DisplayName("절대 수명이 지난 세션은 활동이 있어도 갱신되지 않고(SESSION_EXPIRED) 세션이 종료되며 액세스 토큰 폐기가 요청된다")
    void expiredSessionIsEndedOnRefresh() {
        TokenResponse tokens = newSession();
        backdateSession(tokens.sessionId(), Duration.ofDays(ABSOLUTE_DAYS + 1));

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(tokens.refreshToken())))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SESSION_EXPIRED));

        assertThat(sessionActive(tokens.sessionId())).isFalse();
        verify(killSwitch).publishSessionRevoked(any(), eq(tokens.sessionId()), eq("SESSION_MAX_LIFETIME"));
        // 같은 토큰으로 다시 시도해도 계속 거부된다
        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(tokens.refreshToken()))).isInstanceOf(AuthException.class);
    }

    @Test
    @DisplayName("끝이 가까운 세션의 새 리프레시 토큰은 세션의 절대 수명 끝(약 1일 뒤)을 넘지 않는다(30일이 아니다)")
    void newRefreshTokenIsCappedAtTheAbsoluteEnd() {
        TokenResponse tokens = newSession();
        backdateSession(tokens.sessionId(), Duration.ofDays(ABSOLUTE_DAYS).minusDays(1));

        TokenResponse refreshed = authService.refresh(new RefreshTokenRequest(tokens.refreshToken()));

        // 로그인 때 만든 첫 토큰(30일 뒤 만료)은 회전으로 폐기됐으므로 아직 유효한 새 토큰만 본다
        Timestamp latest = jdbc.queryForObject("select max(expires_at) from refresh_tokens where session_id = ? and is_revoked = false", Timestamp.class, tokens.sessionId());
        assertThat(latest.toInstant()).isBefore(Instant.now().plus(Duration.ofDays(2)));
        assertThat(latest.toInstant()).isAfter(Instant.now().plus(Duration.ofHours(12)));
        assertThat(refreshed.accessToken()).isNotBlank();
    }

    @Test
    @DisplayName("다시 로그인하면 새 세션이라 절대 수명이 처음부터 시작한다")
    void loginStartsAFreshLifetime() {
        TokenResponse old = newSession();
        backdateSession(old.sessionId(), Duration.ofDays(ABSOLUTE_DAYS + 5));
        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(old.refreshToken()))).isInstanceOf(AuthException.class);

        TokenResponse fresh = authService.login(new LoginRequest(email, PASSWORD, "test"), "127.0.0.1", "UA-" + UUID.randomUUID()).tokens();

        assertThat(authService.refresh(new RefreshTokenRequest(fresh.refreshToken())).accessToken()).isNotBlank();
    }
}

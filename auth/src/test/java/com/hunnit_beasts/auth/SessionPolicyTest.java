package com.hunnit_beasts.auth;

import com.hunnit_beasts.auth.core.redis.KillSwitchPublisher;
import com.hunnit_beasts.auth.core.token.RefreshTokenService;
import com.hunnit_beasts.auth.domain.auth.dto.LoginRequest;
import com.hunnit_beasts.auth.domain.auth.dto.SignUpRequest;
import com.hunnit_beasts.auth.domain.auth.dto.TokenResponse;
import com.hunnit_beasts.auth.domain.auth.service.AuthService;
import com.hunnit_beasts.auth.domain.session.dto.SessionResponse;
import com.hunnit_beasts.auth.domain.session.service.SessionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** A3: 세션 수 상한, 동일 기기 재로그인 정리, 슬라이딩 무활동 만료 (상한 3 으로 설정한 컨텍스트). */
@SpringBootTest(properties = {
        "doro.iam.session.max-active-sessions-per-user=3",
        "doro.iam.session.inactivity-timeout-seconds=1000"
})
@ActiveProfiles("test")
class SessionPolicyTest {

    private static final String PASSWORD = "Password123!";

    @Autowired
    private AuthService authService;
    @Autowired
    private SessionService sessionService;
    @Autowired
    private RefreshTokenService refreshTokenService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockitoBean
    private KillSwitchPublisher killSwitchPublisher;

    private UUID newUser(String email) {
        return authService.signup(new SignUpRequest(email, PASSWORD, "Session Tester"));
    }

    private TokenResponse login(String email, String ip, String ua) throws InterruptedException {
        Thread.sleep(5); // createdAt 정렬이 겹치지 않도록
        return authService.login(new LoginRequest(email, PASSWORD, null), ip, ua).tokens();
    }

    private int activeRefreshTokens(UUID sessionId) {
        Integer n = jdbcTemplate.queryForObject(
                "select count(*) from refresh_tokens where session_id = ? and is_revoked = false", Integer.class, sessionId);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("A3a: 상한(3)을 넘기면 가장 오래된 세션이 SESSION_LIMIT_EXCEEDED 로 종료되고 리프레시 토큰도 폐기된다")
    void oldestSessionsAreRevokedOverTheLimit() throws Exception {
        String email = "limit-" + UUID.randomUUID() + "@doro.local";
        UUID userId = newUser(email);

        TokenResponse s1 = login(email, "10.0.0.1", "ua-1");
        TokenResponse s2 = login(email, "10.0.0.2", "ua-2");
        TokenResponse s3 = login(email, "10.0.0.3", "ua-3");
        assertThat(sessionService.getActiveSessions(userId)).hasSize(3);

        TokenResponse s4 = login(email, "10.0.0.4", "ua-4");

        List<UUID> active = sessionService.getActiveSessions(userId).stream().map(SessionResponse::sessionId).toList();
        assertThat(active).containsExactlyInAnyOrder(s2.sessionId(), s3.sessionId(), s4.sessionId());
        assertThat(activeRefreshTokens(s1.sessionId())).isZero();
        assertThat(activeRefreshTokens(s4.sessionId())).isEqualTo(1);
        verify(killSwitchPublisher).publishSessionRevoked(eq(userId), eq(s1.sessionId()), eq("SESSION_LIMIT_EXCEEDED"));
    }

    @Test
    @DisplayName("A3b: 동일 IP+UA 재로그인 시 이전 세션은 리프레시 토큰 폐기와 킬스위치 발행까지 함께 처리된다")
    void sameDeviceReloginRevokesPreviousSessionFully() throws Exception {
        String email = "same-device-" + UUID.randomUUID() + "@doro.local";
        UUID userId = newUser(email);

        TokenResponse first = login(email, "10.1.0.1", "same-ua");
        assertThat(activeRefreshTokens(first.sessionId())).isEqualTo(1);
        TokenResponse second = login(email, "10.1.0.1", "same-ua");

        assertThat(sessionService.getActiveSessions(userId)).extracting(SessionResponse::sessionId)
                .containsExactly(second.sessionId());
        assertThat(activeRefreshTokens(first.sessionId())).isZero();
        verify(killSwitchPublisher).publishSessionRevoked(eq(userId), eq(first.sessionId()), eq("REPLACED_BY_NEW_LOGIN"));
        verify(killSwitchPublisher, never()).publishSessionRevoked(any(), eq(second.sessionId()), any());
    }

    @Test
    @DisplayName("A3c: 리프레시(활동) 시 세션 만료 시각이 now + inactivity-timeout 으로 연장된다 (슬라이딩)")
    void refreshSlidesSessionExpiry() throws Exception {
        String email = "slide-" + UUID.randomUUID() + "@doro.local";
        newUser(email);
        TokenResponse tokens = login(email, "10.2.0.1", "slide-ua");

        // 만료가 임박한 상태로 만든다 (60초 후)
        Instant nearExpiry = Instant.now().plusSeconds(60);
        jdbcTemplate.update("update user_sessions set expires_at = ? where id = ?",
                java.sql.Timestamp.from(nearExpiry), tokens.sessionId());

        Instant before = Instant.now();
        refreshTokenService.rotateRefreshToken(tokens.refreshToken());

        Instant after = jdbcTemplate.queryForObject("select expires_at from user_sessions where id = ?",
                java.sql.Timestamp.class, tokens.sessionId()).toInstant();
        assertThat(after).isAfterOrEqualTo(before.plusSeconds(1000)).isAfter(nearExpiry);
    }
}

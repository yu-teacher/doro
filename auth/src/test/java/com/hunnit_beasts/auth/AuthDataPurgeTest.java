package com.hunnit_beasts.auth;

import com.hunnit_beasts.auth.core.token.AuthDataPurgeService;
import com.hunnit_beasts.auth.core.token.RefreshTokenService;
import com.hunnit_beasts.auth.domain.session.entity.UserSession;
import com.hunnit_beasts.auth.domain.session.repository.UserSessionRepository;
import com.hunnit_beasts.auth.domain.user.entity.User;
import com.hunnit_beasts.auth.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 만료·폐기 토큰과 비활성 세션의 배치 삭제(보존 기간 이후)를 검증한다. */
@SpringBootTest(properties = {
        "doro.iam.token-purge.retention-days=90",
        "doro.iam.token-purge.batch-size=2"
})
@ActiveProfiles("test")
class AuthDataPurgeTest {

    @Autowired
    private AuthDataPurgeService purgeService;
    @Autowired
    private RefreshTokenService refreshTokenService;
    @Autowired
    private UserSessionRepository sessionRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID newUser() {
        return userRepository.saveAndFlush(User.builder()
                .email("purge-" + UUID.randomUUID() + "@doro.test").name("purge").build()).getId();
    }

    private UserSession newSession(UUID userId, boolean active, Duration lastActiveAgo) {
        UserSession s = sessionRepository.saveAndFlush(UserSession.builder()
                .userId(userId).userIndex(0).expiresAt(Instant.now().plus(Duration.ofDays(1))).build());
        jdbc.update("UPDATE user_sessions SET is_active = ?, last_active_at = ? WHERE id = ?",
                active, Timestamp.from(Instant.now().minus(lastActiveAgo)), s.getId());
        return s;
    }

    /** 토큰 하나를 만들고 폐기 여부, 만료 시각, 발급 시각을 지정한 값으로 맞춘다. */
    private UUID newToken(UUID sessionId, boolean revoked, Duration expiresIn, Duration createdAgo) {
        refreshTokenService.createRefreshToken(sessionId);
        UUID id = jdbc.queryForObject(
                "SELECT id FROM refresh_tokens WHERE session_id = ? ORDER BY created_at DESC LIMIT 1", UUID.class, sessionId);
        jdbc.update("UPDATE refresh_tokens SET is_revoked = ?, expires_at = ?, created_at = ? WHERE id = ?",
                revoked, Timestamp.from(Instant.now().plus(expiresIn)),
                Timestamp.from(Instant.now().minus(createdAgo)), id);
        return id;
    }

    private boolean tokenExists(UUID id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE id = ?", Integer.class, id) > 0;
    }

    private boolean sessionExists(UUID id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_sessions WHERE id = ?", Integer.class, id) > 0;
    }

    @Test
    @DisplayName("보존 기간(90일)이 지난 만료·폐기 토큰과 비활성 세션만 배치로 삭제하고 나머지는 보존한다")
    void purgesOnlyStaleRows() {
        UUID user = newUser();
        // 오래된 비활성 세션 3개(배치 크기 2 → 여러 배치) + 토큰
        UUID[] oldSessions = new UUID[3];
        UUID[] oldSessionTokens = new UUID[3];
        for (int i = 0; i < 3; i++) {
            UserSession s = newSession(user, false, Duration.ofDays(100));
            oldSessions[i] = s.getId();
            oldSessionTokens[i] = newToken(s.getId(), true, Duration.ofDays(-70), Duration.ofDays(100));
        }
        // 최근 비활성 세션: 재사용 탐지를 위해 폐기 토큰과 함께 보존
        UserSession recentInactive = newSession(user, false, Duration.ofDays(10));
        UUID recentInactiveToken = newToken(recentInactive.getId(), true, Duration.ofDays(20), Duration.ofDays(10));
        // 활성 세션: 만료 후 오래된 토큰, 오래전 폐기 토큰은 삭제 / 최근 폐기·활성 토큰은 보존
        UserSession active = newSession(user, true, Duration.ZERO);
        UUID expiredOld = newToken(active.getId(), false, Duration.ofDays(-100), Duration.ofDays(130));
        UUID revokedOld = newToken(active.getId(), true, Duration.ofDays(-60), Duration.ofDays(100));
        UUID revokedRecent = newToken(active.getId(), true, Duration.ofDays(20), Duration.ofDays(10));
        UUID live = newToken(active.getId(), false, Duration.ofDays(30), Duration.ZERO);

        int[] deleted = purgeService.purge(Instant.now());

        assertThat(deleted[0]).isGreaterThanOrEqualTo(5);
        assertThat(deleted[1]).isGreaterThanOrEqualTo(3);
        for (int i = 0; i < 3; i++) {
            assertThat(sessionExists(oldSessions[i])).isFalse();
            assertThat(tokenExists(oldSessionTokens[i])).isFalse();
        }
        assertThat(tokenExists(expiredOld)).isFalse();
        assertThat(tokenExists(revokedOld)).isFalse();
        assertThat(sessionExists(recentInactive.getId())).isTrue();
        assertThat(tokenExists(recentInactiveToken)).isTrue();
        assertThat(sessionExists(active.getId())).isTrue();
        assertThat(tokenExists(revokedRecent)).isTrue();
        assertThat(tokenExists(live)).isTrue();
    }

    @Test
    @DisplayName("끄면(enabled=false) 스케줄 실행은 아무것도 지우지 않는다")
    void disabledDoesNothing() {
        UUID user = newUser();
        UserSession old = newSession(user, false, Duration.ofDays(200));
        ReflectionTestUtils.setField(purgeService, "enabled", false);
        try {
            purgeService.purgeScheduled();
        } finally {
            ReflectionTestUtils.setField(purgeService, "enabled", true);
        }
        assertThat(sessionExists(old.getId())).isTrue();
    }
}

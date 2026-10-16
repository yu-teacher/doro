package com.hunnit_beasts.auth.core.token;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.domain.session.entity.UserSession;
import com.hunnit_beasts.auth.domain.user.entity.User;
import com.hunnit_beasts.auth.domain.user.repository.UserRepository;
import com.hunnit_beasts.auth.domain.session.repository.UserSessionRepository;
import com.hunnit_beasts.auth.domain.token.repository.RefreshTokenRepository;
import com.hunnit_beasts.auth.core.redis.KillSwitchPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class RefreshTokenServiceTest {

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserSessionRepository userSessionRepository;

    @Autowired
    private UserRepository userRepository;

    /** 세션은 users 를 FK 로 참조한다 (실제 PostgreSQL 스키마에서는 FK 가 강제된다). */
    private UUID persistedUserId() {
        return userRepository.saveAndFlush(User.builder()
                .email("rt-" + UUID.randomUUID() + "@doro.test")
                .name("rt")
                .build()).getId();
    }

    @Test
    @DisplayName("정상 Refresh Token 발급 및 회전(RTR) 검증")
    void testCreateAndRotateRefreshToken() {
        UUID userId = persistedUserId();
        UserSession session = userSessionRepository.save(UserSession.builder()
                .userId(userId)
                .userIndex(0)
                .expiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
                .build());

        String initialToken = refreshTokenService.createRefreshToken(session.getId());
        assertThat(initialToken).isNotBlank();

        // 1차 회전 (RTR)
        RefreshTokenService.RotatedTokenResult result1 = refreshTokenService.rotateRefreshToken(initialToken);
        assertThat(result1.newRefreshToken()).isNotEqualTo(initialToken);
        assertThat(result1.session().getId()).isEqualTo(session.getId());

        // 2차 회전 (RTR)
        RefreshTokenService.RotatedTokenResult result2 = refreshTokenService.rotateRefreshToken(result1.newRefreshToken());
        assertThat(result2.newRefreshToken()).isNotEqualTo(result1.newRefreshToken());
    }

    @Test
    @DisplayName("토큰 재사용 공격 감지 시 패밀리 전체 무효화 및 예외 발생 검증")
    void testReuseDetection() {
        UUID userId = persistedUserId();
        UserSession session = userSessionRepository.save(UserSession.builder()
                .userId(userId)
                .userIndex(0)
                .expiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
                .build());

        String token1 = refreshTokenService.createRefreshToken(session.getId());

        // 1회 회전 -> token1은 만료/폐기됨
        RefreshTokenService.RotatedTokenResult result = refreshTokenService.rotateRefreshToken(token1);
        String token2 = result.newRefreshToken();

        // 해커가 이미 사용된 token1을 다시 제출한 경우 -> 재사용 탐지!
        assertThatThrownBy(() -> refreshTokenService.rotateRefreshToken(token1))
                .isInstanceOf(AuthException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TOKEN_REUSE_DETECTED);

        // 공격 탐지 후 정상 사용자(token2)도 보안을 위해 세션과 함께 강제 무효화되어야 함
        assertThatThrownBy(() -> refreshTokenService.rotateRefreshToken(token2))
                .isInstanceOf(AuthException.class);

        // 세션도 비활성화되었는지 확인
        UserSession deactivatedSession = userSessionRepository.findById(session.getId()).orElseThrow();
        assertThat(deactivatedSession.isActive()).isFalse();
    }

    private static ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captureLogs(Class<?> type) {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(type);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private UserSession newActiveSession() {
        return userSessionRepository.save(UserSession.builder()
                .userId(persistedUserId()).userIndex(0)
                .expiresAt(Instant.now().plus(7, ChronoUnit.DAYS)).build());
    }

    @Test
    @DisplayName("세션이 이미 비활성(로그아웃·비밀번호 변경 등)이면 폐기된 토큰 재제시는 SESSION_EXPIRED 이고 재사용 경보를 내지 않는다")
    void revokedTokenOnInactiveSessionIsNotReuseAttack() {
        UserSession session = newActiveSession();
        String token = refreshTokenService.createRefreshToken(session.getId());

        // 정상 로그아웃: 토큰 폐기 + 세션 비활성화
        refreshTokenService.revokeAllForSession(session.getId());
        UserSession loaded = userSessionRepository.findById(session.getId()).orElseThrow();
        loaded.deactivate();
        userSessionRepository.saveAndFlush(loaded);

        var logs = captureLogs(RefreshTokenService.class);
        assertThatThrownBy(() -> refreshTokenService.rotateRefreshToken(token))
                .isInstanceOf(AuthException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SESSION_EXPIRED);
        assertThat(logs.list).noneMatch(e -> e.getFormattedMessage().contains("SECURITY ALERT"));
    }

    @Test
    @DisplayName("회전으로 폐기된 토큰이 활성 세션에서 재제시되면 재사용 공격으로 판정해 세션을 끝낸다")
    void rotatedTokenOnActiveSessionIsReuseAttack() {
        UserSession session = newActiveSession();
        String first = refreshTokenService.createRefreshToken(session.getId());
        refreshTokenService.rotateRefreshToken(first);

        var logs = captureLogs(RefreshTokenService.class);
        assertThatThrownBy(() -> refreshTokenService.rotateRefreshToken(first))
                .isInstanceOf(AuthException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TOKEN_REUSE_DETECTED);
        assertThat(logs.list).anyMatch(e -> e.getFormattedMessage().contains("SECURITY ALERT"));
        assertThat(userSessionRepository.findById(session.getId()).orElseThrow().isActive()).isFalse();
    }
}

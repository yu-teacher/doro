package com.hunnit_beasts.auth.core.token;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.domain.session.entity.UserSession;
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

    @Test
    @DisplayName("정상 Refresh Token 발급 및 회전(RTR) 검증")
    void testCreateAndRotateRefreshToken() {
        UUID userId = UUID.randomUUID();
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
        UUID userId = UUID.randomUUID();
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
}

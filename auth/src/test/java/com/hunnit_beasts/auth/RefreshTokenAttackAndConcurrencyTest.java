package com.hunnit_beasts.auth;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.core.token.RefreshTokenService;
import com.hunnit_beasts.auth.domain.auth.dto.LoginRequest;
import com.hunnit_beasts.auth.domain.auth.dto.LoginResponse;
import com.hunnit_beasts.auth.domain.auth.dto.RefreshTokenRequest;
import com.hunnit_beasts.auth.domain.auth.dto.SignUpRequest;
import com.hunnit_beasts.auth.domain.auth.service.AuthService;
import com.hunnit_beasts.auth.domain.session.entity.UserSession;
import com.hunnit_beasts.auth.domain.session.repository.UserSessionRepository;
import com.hunnit_beasts.auth.domain.token.entity.RefreshToken;
import com.hunnit_beasts.auth.domain.token.repository.RefreshTokenRepository;
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
class RefreshTokenAttackAndConcurrencyTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserSessionRepository sessionRepository;

    @Test
    @DisplayName("보안 침해 시나리오: 다단계 토큰 회전(A->B->C) 후 해커의 과거 토큰(A) 탈취 공격 시 정상 토큰(C) 및 세션 즉시 올킬")
    void testMultiStepTokenHijackingRevocation() {
        String email = "token.hijack@doro.local";
        authService.signup(new SignUpRequest(email, "Password123!", "Hijack User"));

        // 1. 초기 로그인 (Token A 발급)
        LoginResponse loginResp = authService.login(new LoginRequest(email, "Password123!", "Device 1"), "1.1.1.1", "UA");
        String tokenA = loginResp.tokens().refreshToken();
        UUID sessionId = loginResp.tokens().sessionId();

        // 2. 정상 사용자 1차 갱신 (Token A -> Token B)
        String tokenB = authService.refresh(new RefreshTokenRequest(tokenA)).refreshToken();
        assertThat(tokenB).isNotEqualTo(tokenA);

        // 3. 정상 사용자 2차 갱신 (Token B -> Token C)
        String tokenC = authService.refresh(new RefreshTokenRequest(tokenB)).refreshToken();
        assertThat(tokenC).isNotEqualTo(tokenB);

        // 4. 해커가 탈취한 과거 토큰 A로 토큰 갱신 시도!
        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(tokenA)))
                .isInstanceOf(AuthException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TOKEN_REUSE_DETECTED);

        // 5. 보안 격리 검증: 정상 사용자가 가지고 있던 최신 Token C도 즉각 차단되어야 함
        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(tokenC)))
                .isInstanceOf(AuthException.class);

        // 6. 세션 상태도 비활성화(deactivated)되었는지 검증
        UserSession session = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(session.isActive()).isFalse();
    }

    @Test
    @DisplayName("엣지케이스: 유효기간이 지난 만료된 Refresh Token으로 갱신 시도 시 TOKEN_EXPIRED 발생")
    void testExpiredRefreshTokenRejection() {
        String email = "expired.token@doro.local";
        authService.signup(new SignUpRequest(email, "Password123!", "Expired Token User"));

        LoginResponse loginResp = authService.login(new LoginRequest(email, "Password123!", "Device 1"), "1.1.1.1", "UA");
        String token = loginResp.tokens().refreshToken();
        UUID sessionId = loginResp.tokens().sessionId();

        // 토큰 만료일을 과거로 조작
        RefreshToken rtEntity = refreshTokenRepository.findAll().stream()
                .filter(rt -> rt.getSessionId().equals(sessionId) && !rt.isRevoked())
                .findFirst()
                .orElseThrow();
        ReflectionTestUtils.setField(rtEntity, "expiresAt", Instant.now().minus(1, ChronoUnit.HOURS));
        refreshTokenRepository.saveAndFlush(rtEntity);

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(token)))
                .isInstanceOf(AuthException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TOKEN_EXPIRED);
    }

    @Test
    @DisplayName("엣지케이스: 존재하지 않거나 무효한 난수 토큰으로 갱신 시도 시 INVALID_TOKEN 발생")
    void testInvalidRandomTokenRejection() {
        String fakeToken = "this-is-a-completely-fake-refresh-token-string-12345";

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(fakeToken)))
                .isInstanceOf(AuthException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_TOKEN);
    }
}

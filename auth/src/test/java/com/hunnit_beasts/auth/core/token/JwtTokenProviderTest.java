package com.hunnit_beasts.auth.core.token;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    private JwtKeyProvider keyProvider;
    private JwtTokenProvider tokenProvider;

    @BeforeEach
    void setUp() {
        keyProvider = new JwtKeyProvider();
        keyProvider.init();

        tokenProvider = new JwtTokenProvider(keyProvider);
        ReflectionTestUtils.setField(tokenProvider, "issuer", "https://auth.doro.local");
        ReflectionTestUtils.setField(tokenProvider, "accessTokenValiditySeconds", 900L);
    }

    @Test
    @DisplayName("비대칭키 기반 Access Token 발급 및 클레임(userId, sessionId, userIndex) 검증")
    void testCreateAndValidateAccessToken() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String email = "test@doro.local";
        int userIndex = 0;

        String token = tokenProvider.createAccessToken(userId, email, sessionId, userIndex);

        assertThat(token).isNotBlank();

        Claims claims = tokenProvider.parseAndValidateToken(token);
        assertThat(tokenProvider.getUserId(claims)).isEqualTo(userId);
        assertThat(tokenProvider.getSessionId(claims)).isEqualTo(sessionId);
        assertThat(tokenProvider.getUserIndex(claims)).isEqualTo(userIndex);
        assertThat(claims.get("email", String.class)).isEqualTo(email);
        assertThat(claims.getIssuer()).isEqualTo("https://auth.doro.local");
    }

    @Test
    @DisplayName("RFC 7517 표준에 맞춘 JWKS 포맷 정상 생성 검증")
    void testJwksGeneration() {
        Map<String, Object> jwks = keyProvider.getJwks();

        assertThat(jwks).containsKey("keys");
        assertThat(jwks.get("keys")).asList().hasSize(1);
    }
}

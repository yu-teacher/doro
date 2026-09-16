package com.hunnit_beasts.auth.domain.oauth.service;

import com.hunnit_beasts.auth.domain.auth.dto.TokenResponse;
import com.hunnit_beasts.auth.domain.oauth.dto.OAuth2TokenRequest;
import com.hunnit_beasts.auth.domain.user.entity.User;
import com.hunnit_beasts.auth.domain.user.entity.UserStatus;
import com.hunnit_beasts.auth.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class OAuth2ServiceTest {

    @Autowired
    private OAuth2Service oAuth2Service;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("OAuth 2.1 PKCE (S256) 기반 인가 코드 발급 및 토큰 교환 성공")
    void testPkceFlow() throws Exception {
        User user = userRepository.save(User.builder()
                .email("oauth2user@doro.local")
                .name("OAuth User")
                .status(UserStatus.ACTIVE)
                .build());

        String clientId = "client-app-123";
        String redirectUri = "http://localhost:3000/callback";

        // PKCE verifier and challenge
        String codeVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
        String codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);

        // 1. Authorization Code 발급
        String authCode = oAuth2Service.generateAuthorizationCode(clientId, redirectUri, user.getId(), codeChallenge);
        assertThat(authCode).isNotBlank();

        // 2. Token 교환 (PKCE code_verifier 제공)
        OAuth2TokenRequest tokenRequest = new OAuth2TokenRequest(
                "authorization_code",
                authCode,
                redirectUri,
                clientId,
                codeVerifier
        );

        TokenResponse tokenResponse = oAuth2Service.exchangeCode(tokenRequest);
        assertThat(tokenResponse.accessToken()).isNotBlank();
        assertThat(tokenResponse.refreshToken()).isNotBlank();
    }

    @Test
    @DisplayName("OIDC Discovery 메타데이터 조회 검증")
    void testOidcDiscovery() {
        Map<String, Object> config = oAuth2Service.getOidcConfiguration();
        assertThat(config).containsKey("authorization_endpoint");
        assertThat(config).containsKey("token_endpoint");
        assertThat(config).containsKey("jwks_uri");
        assertThat(config.get("code_challenge_methods_supported")).asList().contains("S256");
    }
}

package com.hunnit_beasts.auth;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.domain.auth.dto.TokenResponse;
import com.hunnit_beasts.auth.domain.oauth.dto.OAuth2TokenRequest;
import com.hunnit_beasts.auth.domain.oauth.service.OAuth2Service;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class OAuth2PkceEdgeCaseTest {

    @Autowired
    private OAuth2Service oAuth2Service;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("PKCE 엣지케이스: 1글자 변조된 code_verifier로 토큰 교환 시도 시 차단")
    void testTamperedCodeVerifierFails() throws Exception {
        User user = userRepository.save(User.builder()
                .email("pkce.tamper@doro.local")
                .name("PKCE User")
                .status(UserStatus.ACTIVE)
                .build());

        String clientId = "my-client";
        String redirectUri = "https://app.doro.local/callback";

        String correctVerifier = "N2I4OWUzOWUtMzZkMS00MzdiLWIwYjQtNDI4ZjMyNTc0YTY3";
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        String codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                md.digest(correctVerifier.getBytes(StandardCharsets.US_ASCII)));

        String code = oAuth2Service.generateAuthorizationCode(clientId, redirectUri, user.getId(), codeChallenge);

        // 1글자 변조된 verifier
        String tamperedVerifier = correctVerifier + "X";
        OAuth2TokenRequest request = new OAuth2TokenRequest(
                "authorization_code", code, redirectUri, clientId, tamperedVerifier);

        assertThatThrownBy(() -> oAuth2Service.exchangeCode(request))
                .isInstanceOf(AuthException.class);
    }

    @Test
    @DisplayName("PKCE 엣지케이스: 1회용 인가 코드(Authorization Code) 재사용 공격 차단")
    void testAuthorizationCodeOneTimeUseOnly() throws Exception {
        User user = userRepository.save(User.builder()
                .email("pkce.reuse@doro.local")
                .name("PKCE Reuse User")
                .status(UserStatus.ACTIVE)
                .build());

        String clientId = "my-client-2";
        String redirectUri = "https://app.doro.local/callback";
        String verifier = "CorrectVerifier123456789012345678901234567890";
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        String codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                md.digest(verifier.getBytes(StandardCharsets.US_ASCII)));

        String code = oAuth2Service.generateAuthorizationCode(clientId, redirectUri, user.getId(), codeChallenge);

        OAuth2TokenRequest request = new OAuth2TokenRequest(
                "authorization_code", code, redirectUri, clientId, verifier);

        // 1회차 교환 성공
        TokenResponse response1 = oAuth2Service.exchangeCode(request);
        assertThat(response1.accessToken()).isNotBlank();

        // 2회차 동일 코드로 재사용 시도 -> 즉각 차단
        assertThatThrownBy(() -> oAuth2Service.exchangeCode(request))
                .isInstanceOf(AuthException.class);
    }

    @Test
    @DisplayName("PKCE 엣지케이스: client_id 또는 redirect_uri 불일치 시 차단")
    void testClientIdOrRedirectUriMismatchFails() throws Exception {
        User user = userRepository.save(User.builder()
                .email("pkce.mismatch@doro.local")
                .name("Mismatch User")
                .status(UserStatus.ACTIVE)
                .build());

        String verifier = "RandomVerifier123456789012345678901234567890";
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        String codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                md.digest(verifier.getBytes(StandardCharsets.US_ASCII)));

        String code = oAuth2Service.generateAuthorizationCode("client-A", "https://app-a.com/cb", user.getId(), codeChallenge);

        // 다른 client_id로 교환 시도
        OAuth2TokenRequest wrongClientReq = new OAuth2TokenRequest(
                "authorization_code", code, "https://app-a.com/cb", "client-B", verifier);
        assertThatThrownBy(() -> oAuth2Service.exchangeCode(wrongClientReq))
                .isInstanceOf(AuthException.class);
    }
}

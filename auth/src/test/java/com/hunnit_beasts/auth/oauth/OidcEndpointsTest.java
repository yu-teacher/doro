package com.hunnit_beasts.auth.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** OIDC: id_token 서명/클레임, UserInfo, Discovery. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OidcEndpointsTest extends OAuthTestSupport {

    @Test
    @DisplayName("id_token: JWKS 키로 검증되고 iss/aud/sub/nonce/exp/auth_time 및 email/name 클레임을 가진다")
    void idTokenVerifiesAgainstJwks() throws Exception {
        String clientId = uniqueClientId();
        registerClient(clientId, List.of(GOOD_REDIRECT));
        Login me = signupAndLogin();

        JsonNode tokens = exchange(me, clientId, GOOD_REDIRECT, "openid profile email", "nonce-xyz");
        Claims claims = verifyIdToken(tokens.path("id_token").asText(), clientId).getPayload();

        assertThat(claims.getIssuer()).isEqualTo(ISSUER);
        assertThat(claims.getAudience()).containsExactly(clientId);
        assertThat(claims.getSubject()).isEqualTo(me.userId());
        assertThat(claims.get("nonce", String.class)).isEqualTo("nonce-xyz");
        assertThat(claims.get("email", String.class)).isEqualTo(me.email());
        assertThat(claims.get("email_verified", Boolean.class)).isFalse();
        assertThat(claims.get("name", String.class)).isEqualTo("OAuth Tester");
        assertThat(claims.get("sid", String.class)).isNotBlank();
        assertThat(claims.get("auth_time", Number.class).longValue()).isPositive();
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
        long ttlSeconds = (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000;
        assertThat(ttlSeconds).isEqualTo(tokens.path("expires_in").asLong());
    }

    @Test
    @DisplayName("id_token: nonce 가 없으면 nonce 클레임이 없고, 스코프에 따라 email/name 을 선택적으로 담는다")
    void idTokenClaimsFollowScope() throws Exception {
        String clientId = uniqueClientId();
        registerClient(clientId, List.of(GOOD_REDIRECT));
        Login me = signupAndLogin();

        Claims onlyOpenid = verifyIdToken(exchange(me, clientId, GOOD_REDIRECT, "openid", null).path("id_token").asText(), clientId).getPayload();
        assertThat(onlyOpenid).doesNotContainKeys("nonce", "email", "name", "picture");

        Claims withEmail = verifyIdToken(exchange(me, clientId, GOOD_REDIRECT, "openid email", null).path("id_token").asText(), clientId).getPayload();
        assertThat(withEmail).containsKey("email").doesNotContainKey("name");
    }

    @Test
    @DisplayName("id_token/userinfo 의 picture 는 http(s) URL 만 허용하고 data: URL 은 절대 싣지 않는다")
    void pictureOnlyForHttpUrls() throws Exception {
        String clientId = uniqueClientId();
        registerClient(clientId, List.of(GOOD_REDIRECT));
        Login me = signupAndLogin();

        jdbcTemplate.update("update users set profile_image_url = ? where id = cast(? as uuid)",
                "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==", me.userId());
        JsonNode dataTokens = exchange(me, clientId, GOOD_REDIRECT, "openid profile", null);
        Claims dataClaims = verifyIdToken(dataTokens.path("id_token").asText(), clientId).getPayload();
        assertThat(dataClaims).doesNotContainKey("picture");
        assertThat(dataTokens.path("id_token").asText()).doesNotContain("data:image");
        mockMvc.perform(get("/oauth2/userinfo").header("Authorization", "Bearer " + dataTokens.path("access_token").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.picture").doesNotExist());

        jdbcTemplate.update("update users set profile_image_url = ? where id = cast(? as uuid)", "https://cdn.example/me.png", me.userId());
        JsonNode httpTokens = exchange(me, clientId, GOOD_REDIRECT, "openid profile", null);
        assertThat(verifyIdToken(httpTokens.path("id_token").asText(), clientId).getPayload().get("picture", String.class))
                .isEqualTo("https://cdn.example/me.png");
        mockMvc.perform(get("/oauth2/userinfo").header("Authorization", "Bearer " + httpTokens.path("access_token").asText()))
                .andExpect(jsonPath("$.picture").value("https://cdn.example/me.png"));

        jdbcTemplate.update("update users set profile_image_url = ? where id = cast(? as uuid)", "javascript:alert(1)", me.userId());
        mockMvc.perform(get("/oauth2/userinfo").header("Authorization", "Bearer " + httpTokens.path("access_token").asText()))
                .andExpect(jsonPath("$.picture").doesNotExist());
    }

    @Test
    @DisplayName("userinfo: Bearer 필수(없거나 잘못되면 401), 유효하면 sub/email/name 반환")
    void userInfoRequiresBearer() throws Exception {
        String clientId = uniqueClientId();
        registerClient(clientId, List.of(GOOD_REDIRECT));
        Login me = signupAndLogin();
        JsonNode tokens = exchange(me, clientId, GOOD_REDIRECT, "openid", null);

        mockMvc.perform(get("/oauth2/userinfo")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/oauth2/userinfo").header("Authorization", "Bearer nope")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/oauth2/userinfo").header("Authorization", "Bearer " + tokens.path("access_token").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sub").value(me.userId()))
                .andExpect(jsonPath("$.email").value(me.email()))
                .andExpect(jsonPath("$.name").value("OAuth Tester"));
    }

    @Test
    @DisplayName("회수(로그아웃)된 세션의 액세스 토큰으로는 userinfo 도 인가도 받을 수 없다")
    void revokedSessionTokenIsRejected() throws Exception {
        String clientId = uniqueClientId();
        registerClient(clientId, List.of(GOOD_REDIRECT));
        Login me = signupAndLogin();
        JsonNode tokens = exchange(me, clientId, GOOD_REDIRECT, "openid", null);
        String accessToken = tokens.path("access_token").asText();

        jdbcTemplate.update("update user_sessions set is_active = false where user_id = cast(? as uuid)", me.userId());
        mockMvc.perform(get("/oauth2/userinfo").header("Authorization", "Bearer " + accessToken)).andExpect(status().isUnauthorized());
        mockMvc.perform(authorizeRequest(me, clientId, GOOD_REDIRECT, "E9Melhoa2OwvFrGMTJguCH5rtx64FIbEIqiPQsjzkxo"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Discovery 문서가 실제 지원 내용을 그대로 광고한다")
    void discoveryIsTruthful() throws Exception {
        mockMvc.perform(get("/.well-known/openid-configuration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value(ISSUER))
                .andExpect(jsonPath("$.authorization_endpoint").value(ISSUER + "/oauth2/authorize"))
                .andExpect(jsonPath("$.token_endpoint").value(ISSUER + "/oauth2/token"))
                .andExpect(jsonPath("$.userinfo_endpoint").value(ISSUER + "/oauth2/userinfo"))
                .andExpect(jsonPath("$.jwks_uri").value(ISSUER + "/.well-known/jwks.json"))
                .andExpect(jsonPath("$.response_types_supported").value(org.hamcrest.Matchers.contains("code")))
                .andExpect(jsonPath("$.response_modes_supported").value(org.hamcrest.Matchers.contains("query")))
                .andExpect(jsonPath("$.grant_types_supported").value(org.hamcrest.Matchers.contains("authorization_code", "refresh_token")))
                .andExpect(jsonPath("$.subject_types_supported").value(org.hamcrest.Matchers.contains("public")))
                .andExpect(jsonPath("$.id_token_signing_alg_values_supported").value(org.hamcrest.Matchers.contains("RS256")))
                .andExpect(jsonPath("$.scopes_supported").value(org.hamcrest.Matchers.contains("openid", "profile", "email")))
                .andExpect(jsonPath("$.code_challenge_methods_supported").value(org.hamcrest.Matchers.contains("S256")))
                .andExpect(jsonPath("$.token_endpoint_auth_methods_supported").value(org.hamcrest.Matchers.contains("none", "client_secret_basic", "client_secret_post")))
                .andExpect(jsonPath("$.claims_supported").isArray());
    }
}

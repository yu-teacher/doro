package com.hunnit_beasts.auth.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.hunnit_beasts.auth.core.token.JwtTokenProvider;
import com.hunnit_beasts.auth.infrastructure.guard.GuardClient;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OAuth 로 발급한 토큰은 "리소스 서버(서브 서비스)에서 쓰는 토큰"이다. IAM 자신의 API 를 사용자 권한으로 호출하는
 * 일반 로그인 토큰과 섞이면 안 된다: (1) 액세스 토큰은 role 이 항상 USER 이고 클라이언트 표시(cid)를 가지며
 * userinfo / 세션 확인 외의 IAM API 에서는 인증으로 인정하지 않고, (2) id_token(aud 있음)은 어떤 IAM API 에서도 인증으로 인정하지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OAuthTokenContainmentTest extends OAuthTestSupport {

    @MockitoBean
    private GuardClient guardClient;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private static final String OAUTH_SCOPE = "openid profile email";

    private JsonNode oauthTokensForAdmin(String clientId) throws Exception {
        registerClient(clientId, List.of(GOOD_REDIRECT), "openid", "profile", "email");
        Login admin = signupAndLogin();
        jdbcTemplate.update("update users set role = 'ADMIN' where email = ?", admin.email());
        Login adminLogin = login(admin.email());
        return exchange(adminLogin, clientId, GOOD_REDIRECT, OAUTH_SCOPE, "n-1");
    }

    @Test
    @DisplayName("관리자가 OAuth 로 받은 액세스 토큰은 role=USER, cid=클라이언트 이다 (관리자 권한이 클라이언트로 넘어가지 않는다)")
    void oauthAccessTokenIsDowngradedAndTaggedWithTheClient() throws Exception {
        String clientId = uniqueClientId();
        JsonNode tokens = oauthTokensForAdmin(clientId);

        Claims claims = jwtTokenProvider.parseAndValidateToken(tokens.path("access_token").asText());
        assertThat(claims.get("role", String.class)).isEqualTo("USER");
        assertThat(claims.get("cid", String.class)).isEqualTo(clientId);
    }

    @Test
    @DisplayName("OAuth 액세스 토큰은 IAM 의 일반 API(프로필 수정/세션 종료/2FA/관리자)에서 인증으로 인정되지 않는다")
    void oauthAccessTokenCannotCallIamApis() throws Exception {
        when(guardClient.check(eq("system"), eq("doro"), eq("admin"), anyString())).thenReturn(true);
        String bearer = "Bearer " + oauthTokensForAdmin(uniqueClientId()).path("access_token").asText();

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", bearer)).andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/v1/users/me").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("name", "hacked"))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/sessions").header("Authorization", bearer)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/sessions/revoke-others").param("currentSessionId", "00000000-0000-0000-0000-000000000000")
                .header("Authorization", bearer)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/2fa/setup").header("Authorization", bearer)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/users").header("Authorization", bearer)).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/sessions/00000000-0000-0000-0000-000000000000").header("Authorization", bearer))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("OAuth 액세스 토큰은 userinfo 와 세션 확인(sessions/current)에서는 인정된다 (SSO 와 SDK 폐기 확인에 필요)")
    void oauthAccessTokenWorksWhereResourceServersNeedIt() throws Exception {
        String bearer = "Bearer " + oauthTokensForAdmin(uniqueClientId()).path("access_token").asText();

        mockMvc.perform(get("/oauth2/userinfo").header("Authorization", bearer)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/sessions/current").header("Authorization", bearer)).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("id_token 은 IAM 의 어떤 API 에서도 액세스 토큰으로 인정되지 않는다 (토큰 혼동 방지)")
    void idTokenIsNeverAnAccessToken() throws Exception {
        String bearer = "Bearer " + oauthTokensForAdmin(uniqueClientId()).path("id_token").asText();

        mockMvc.perform(get("/oauth2/userinfo").header("Authorization", bearer)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", bearer)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/sessions/current").header("Authorization", bearer)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("일반 로그인 토큰은 영향이 없다 (role 유지, 모든 API 사용 가능)")
    void firstPartyTokensAreUnaffected() throws Exception {
        Login user = signupAndLogin();
        Claims claims = jwtTokenProvider.parseAndValidateToken(user.accessToken());
        assertThat(claims.get("cid")).isNull();
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + user.accessToken())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/sessions/current").header("Authorization", "Bearer " + user.accessToken()))
                .andExpect(status().isNoContent());
    }
}

package com.hunnit_beasts.auth.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.hunnit_beasts.auth.domain.session.repository.UserSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** refresh_token 그랜트(회전/재사용 탐지/클라이언트 바인딩)와 클라이언트별 OAuth 세션 분리. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OAuthRefreshAndSessionTest extends OAuthTestSupport {

    @Autowired
    private UserSessionRepository sessionRepository;

    private String clientA;
    private String clientB;

    private void registerTwoClients() {
        clientA = uniqueClientId();
        clientB = uniqueClientId();
        registerClient(clientA, List.of(GOOD_REDIRECT));
        registerClient(clientB, List.of("https://other.example/cb"));
    }

    private MvcResult refreshForm(String clientId, String refreshToken) throws Exception {
        return mockMvc.perform(tokenForm("grant_type", "refresh_token", "client_id", clientId, "refresh_token", refreshToken)).andReturn();
    }

    private boolean sessionActive(String sessionId) {
        return sessionRepository.findById(UUID.fromString(sessionId)).orElseThrow().isActive();
    }

    private String sessionIdOfAccessToken(String accessToken) throws Exception {
        String payload = new String(java.util.Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]));
        return objectMapper.readTree(payload).path("sid").asText();
    }

    @Test
    @DisplayName("refresh_token 그랜트: 새 액세스/리프레시 토큰을 발급(회전)하고 폼 응답 형식을 따른다")
    void refreshRotatesTokens() throws Exception {
        registerTwoClients();
        Login me = signupAndLogin();
        JsonNode first = exchange(me, clientA, GOOD_REDIRECT, "openid", null);

        MvcResult result = refreshForm(clientA, first.path("refresh_token").asText());
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode second = json(result);
        assertThat(second.path("access_token").asText()).isNotBlank();
        assertThat(second.path("refresh_token").asText()).isNotBlank().isNotEqualTo(first.path("refresh_token").asText());
        assertThat(second.path("token_type").asText()).isEqualTo("Bearer");
        assertThat(second.has("id_token")).isFalse();

        // 새 액세스 토큰은 실제로 쓸 수 있다
        mockMvc.perform(get("/oauth2/userinfo").header("Authorization", "Bearer " + second.path("access_token").asText()))
                .andExpect(status().isOk());

        // 두 번째 회전도 가능
        assertThat(refreshForm(clientA, second.path("refresh_token").asText()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("JSON 요청의 refresh_token 그랜트도 ApiResponse 계약으로 동작한다")
    void refreshViaJson() throws Exception {
        registerTwoClients();
        Login me = signupAndLogin();
        JsonNode first = exchange(me, clientA, GOOD_REDIRECT, null, null);

        mockMvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantType\":\"refresh_token\",\"clientId\":\"" + clientA + "\",\"refreshToken\":\""
                                + first.path("refresh_token").asText() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty());
    }

    @Test
    @DisplayName("이미 회전된 리프레시 토큰을 재사용하면 기존 재사용 탐지가 동작해 세션과 최신 토큰이 모두 무효화된다")
    void reuseOfRotatedTokenTriggersReuseDetection() throws Exception {
        registerTwoClients();
        Login me = signupAndLogin();
        JsonNode first = exchange(me, clientA, GOOD_REDIRECT, null, null);
        String sessionId = sessionIdOfAccessToken(first.path("access_token").asText());
        String oldRefresh = first.path("refresh_token").asText();

        JsonNode rotated = json(refreshForm(clientA, oldRefresh));
        assertThat(rotated.path("refresh_token").asText()).isNotBlank();
        assertThat(sessionActive(sessionId)).isTrue();

        MvcResult reuse = refreshForm(clientA, oldRefresh);
        assertThat(reuse.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(reuse).path("error").asText()).isEqualTo("invalid_grant");
        assertThat(sessionActive(sessionId)).isFalse();

        // 가족 전체가 폐기되어 정상 최신 토큰도 더는 쓸 수 없다
        assertThat(refreshForm(clientA, rotated.path("refresh_token").asText()).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("JSON 모드에서는 재사용 탐지 오류 코드(TOKEN_REUSE_DETECTED)가 그대로 노출된다")
    void reuseDetectionCodeInJsonMode() throws Exception {
        registerTwoClients();
        Login me = signupAndLogin();
        JsonNode first = exchange(me, clientA, GOOD_REDIRECT, null, null);
        String oldRefresh = first.path("refresh_token").asText();
        refreshForm(clientA, oldRefresh);

        mockMvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantType\":\"refresh_token\",\"clientId\":\"" + clientA + "\",\"refreshToken\":\"" + oldRefresh + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOKEN_REUSE_DETECTED"));
    }

    @Test
    @DisplayName("일반 로그인(비 OAuth)의 리프레시 토큰은 거부되고 소모되지 않는다")
    void nonOAuthRefreshTokenIsRejected() throws Exception {
        registerTwoClients();
        Login me = signupAndLogin();

        MvcResult result = refreshForm(clientA, me.refreshToken());
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(result).path("error").asText()).isEqualTo("invalid_grant");

        // 거부로 토큰이 소모/폐기되지 않았으므로 정상 리프레시 엔드포인트는 여전히 동작한다
        mockMvc.perform(post("/api/v1/auth/token/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + me.refreshToken() + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("다른 클라이언트의 리프레시 토큰은 거부되고 원래 클라이언트에서는 계속 쓸 수 있다")
    void otherClientsRefreshTokenIsRejected() throws Exception {
        registerTwoClients();
        Login me = signupAndLogin();
        JsonNode tokensB = exchange(me, clientB, "https://other.example/cb", null, null);

        MvcResult stolen = refreshForm(clientA, tokensB.path("refresh_token").asText());
        assertThat(stolen.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(stolen).path("error").asText()).isEqualTo("invalid_grant");

        assertThat(refreshForm(clientB, tokensB.path("refresh_token").asText()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("refresh 요청의 필수값/알 수 없는 토큰/허용되지 않은 scope 오류")
    void refreshValidation() throws Exception {
        registerTwoClients();
        Login me = signupAndLogin();
        JsonNode tokens = exchange(me, clientA, GOOD_REDIRECT, null, null);

        MvcResult missing = mockMvc.perform(tokenForm("grant_type", "refresh_token", "client_id", clientA)).andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(missing).path("error").asText()).isEqualTo("invalid_request");

        MvcResult unknown = refreshForm(clientA, "totally-unknown-token");
        assertThat(unknown.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(unknown).path("error").asText()).isEqualTo("invalid_grant");

        MvcResult scope = mockMvc.perform(tokenForm("grant_type", "refresh_token", "client_id", clientA,
                "refresh_token", tokens.path("refresh_token").asText(), "scope", "admin")).andReturn();
        assertThat(scope.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(scope).path("error").asText()).isEqualTo("invalid_scope");
    }

    @Test
    @DisplayName("세션 분리: 클라이언트 A 의 재교환은 A 의 이전 세션만 종료하고 B 의 세션/리프레시 토큰은 유지한다")
    void exchangeReplacesOnlySameClientSession() throws Exception {
        registerTwoClients();
        Login me = signupAndLogin();

        JsonNode a1 = exchange(me, clientA, GOOD_REDIRECT, null, null);
        JsonNode b1 = exchange(me, clientB, "https://other.example/cb", null, null);
        String sessionA1 = sessionIdOfAccessToken(a1.path("access_token").asText());
        String sessionB1 = sessionIdOfAccessToken(b1.path("access_token").asText());
        assertThat(sessionA1).isNotEqualTo(sessionB1);
        assertThat(sessionActive(sessionA1)).isTrue();
        assertThat(sessionActive(sessionB1)).isTrue();

        JsonNode a2 = exchange(me, clientA, GOOD_REDIRECT, null, null);
        String sessionA2 = sessionIdOfAccessToken(a2.path("access_token").asText());

        assertThat(sessionActive(sessionA1)).isFalse();
        assertThat(sessionActive(sessionB1)).isTrue();
        assertThat(sessionActive(sessionA2)).isTrue();
        assertThat(refreshForm(clientA, a1.path("refresh_token").asText()).getResponse().getStatus()).isEqualTo(400);
        assertThat(refreshForm(clientB, b1.path("refresh_token").asText()).getResponse().getStatus()).isEqualTo(200);
        // 일반 로그인 세션도 영향받지 않는다
        assertThat(sessionActive(me.sessionId())).isTrue();
    }
}

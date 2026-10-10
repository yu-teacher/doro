package com.hunnit_beasts.auth.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.hunnit_beasts.auth.domain.oauth.service.ClientSecrets;
import com.hunnit_beasts.auth.infrastructure.guard.GuardClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 기밀 클라이언트: 토큰·폐기 요청에서 client_secret 을 요구하고, 공개 클라이언트(시크릿 없음)는 기존처럼 PKCE 만으로 동작한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OAuthClientSecretTest extends OAuthTestSupport {

    private static final String BASE = "/api/v1/admin/oauth/clients";

    @MockitoBean
    private GuardClient guardClient;

    private String registerConfidential(String clientId, String secret) {
        clientRepository.save(com.hunnit_beasts.auth.domain.oauth.entity.OAuthClient.builder()
                .clientId(clientId).name("Secret App").redirectUris(List.of(GOOD_REDIRECT))
                .allowedScopes(new LinkedHashSet<>(List.of("openid", "profile", "email")))
                .clientSecretHash(ClientSecrets.hash(secret)).build());
        return secret;
    }

    private static String basic(String id, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    private String freshCode(String clientId, Pkce pkce) throws Exception {
        Login me = signupAndLogin();
        return authorizeCode(me, clientId, GOOD_REDIRECT, pkce, "openid", null);
    }

    @Test
    @DisplayName("ClientSecrets: 접두사·길이, 해시 일치, 다른 값 불일치")
    void secretHelper() {
        String s = ClientSecrets.generate();
        assertThat(s).startsWith("dcs_").hasSizeGreaterThan(40);
        assertThat(ClientSecrets.generate()).isNotEqualTo(s);
        assertThat(ClientSecrets.hash(s)).hasSize(64).doesNotContain(s);
        assertThat(ClientSecrets.matches(s, ClientSecrets.hash(s))).isTrue();
        assertThat(ClientSecrets.matches(s + "x", ClientSecrets.hash(s))).isFalse();
        assertThat(ClientSecrets.matches(null, ClientSecrets.hash(s))).isFalse();
    }

    @Test
    @DisplayName("기밀 클라이언트: 시크릿이 없거나 틀리면 401 invalid_client 이고 인가 코드는 소비되지 않는다")
    void missingOrWrongSecretIsRejectedWithoutBurningTheCode() throws Exception {
        String clientId = uniqueClientId();
        String secret = registerConfidential(clientId, ClientSecrets.generate());
        Pkce pkce = newPkce();
        String code = freshCode(clientId, pkce);

        MvcResult none = mockMvc.perform(codeExchangeForm(code, clientId, GOOD_REDIRECT, pkce.verifier())).andReturn();
        assertThat(none.getResponse().getStatus()).isEqualTo(401);
        assertThat(json(none).path("error").asText()).isEqualTo("invalid_client");
        assertThat(none.getResponse().getHeader("WWW-Authenticate")).startsWith("Basic");

        MvcResult wrong = mockMvc.perform(codeExchangeForm(code, clientId, GOOD_REDIRECT, pkce.verifier())
                .param("client_secret", secret + "x")).andReturn();
        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);

        // 코드는 아직 살아 있으므로 올바른 시크릿으로 교환된다
        MvcResult ok = mockMvc.perform(codeExchangeForm(code, clientId, GOOD_REDIRECT, pkce.verifier())
                .param("client_secret", secret)).andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(ok).path("access_token").asText()).isNotBlank();
    }

    @Test
    @DisplayName("client_secret_basic: Authorization Basic 으로 인증, 본문과 섞거나 client_id 가 다르면 400")
    void basicAuthentication() throws Exception {
        String clientId = uniqueClientId();
        String secret = registerConfidential(clientId, ClientSecrets.generate());

        Pkce p1 = newPkce();
        MvcResult ok = mockMvc.perform(codeExchangeForm(freshCode(clientId, p1), clientId, GOOD_REDIRECT, p1.verifier())
                .header("Authorization", basic(clientId, secret))).andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);

        Pkce p2 = newPkce();
        String code2 = freshCode(clientId, p2);
        MvcResult mixed = mockMvc.perform(codeExchangeForm(code2, clientId, GOOD_REDIRECT, p2.verifier())
                .header("Authorization", basic(clientId, secret)).param("client_secret", secret)).andReturn();
        assertThat(mixed.getResponse().getStatus()).isEqualTo(400);

        MvcResult otherId = mockMvc.perform(codeExchangeForm(code2, clientId, GOOD_REDIRECT, p2.verifier())
                .header("Authorization", basic("someone-else", secret))).andReturn();
        assertThat(otherId.getResponse().getStatus()).isEqualTo(400);

        MvcResult garbage = mockMvc.perform(codeExchangeForm(code2, clientId, GOOD_REDIRECT, p2.verifier())
                .header("Authorization", "Basic !!!not-base64")).andReturn();
        assertThat(garbage.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("리프레시 갱신과 폐기도 시크릿을 요구한다")
    void refreshAndRevokeRequireTheSecret() throws Exception {
        String clientId = uniqueClientId();
        String secret = registerConfidential(clientId, ClientSecrets.generate());
        Pkce pkce = newPkce();
        JsonNode tokens = json(mockMvc.perform(codeExchangeForm(freshCode(clientId, pkce), clientId, GOOD_REDIRECT, pkce.verifier())
                .param("client_secret", secret)).andReturn());
        String refresh = tokens.path("refresh_token").asText();

        assertThat(mockMvc.perform(tokenForm("grant_type", "refresh_token", "client_id", clientId, "refresh_token", refresh))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        MvcResult rotated = mockMvc.perform(tokenForm("grant_type", "refresh_token", "client_id", clientId,
                "refresh_token", refresh, "client_secret", secret)).andReturn();
        assertThat(rotated.getResponse().getStatus()).isEqualTo(200);
        String newRefresh = json(rotated).path("refresh_token").asText();

        MockHttpServletRequestBuilder noSecret = post("/oauth2/revoke").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("token", newRefresh).param("client_id", clientId);
        assertThat(mockMvc.perform(noSecret).andReturn().getResponse().getStatus()).isEqualTo(401);
        MockHttpServletRequestBuilder withSecret = post("/oauth2/revoke").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("token", newRefresh).param("client_id", clientId).param("client_secret", secret);
        assertThat(mockMvc.perform(withSecret).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("공개 클라이언트는 그대로: 시크릿 없이 PKCE 만으로 교환된다")
    void publicClientUnchanged() throws Exception {
        String clientId = uniqueClientId();
        registerClient(clientId, List.of(GOOD_REDIRECT));
        Login me = signupAndLogin();
        assertThat(exchange(me, clientId, GOOD_REDIRECT, "openid", null).path("access_token").asText()).isNotBlank();
    }

    @Test
    @DisplayName("JSON 토큰 요청도 clientSecret 필드로 인증한다")
    void jsonTokenRequest() throws Exception {
        String clientId = uniqueClientId();
        String secret = registerConfidential(clientId, ClientSecrets.generate());
        Pkce pkce = newPkce();
        String code = freshCode(clientId, pkce);
        Map<String, String> body = new java.util.HashMap<>(Map.of("grantType", "authorization_code", "code", code,
                "redirectUri", GOOD_REDIRECT, "clientId", clientId, "codeVerifier", pkce.verifier()));

        MvcResult without = mockMvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))).andReturn();
        assertThat(without.getResponse().getStatus()).isEqualTo(400); // JSON 계약은 클라이언트 오류를 400 으로 둔다

        body.put("clientSecret", secret);
        MvcResult with = mockMvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))).andReturn();
        assertThat(with.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("관리자 API: confidential 등록 시 시크릿을 한 번만 돌려주고, 목록엔 없으며, 회전하면 이전 시크릿이 무효가 된다")
    void adminCreateAndRotate() throws Exception {
        when(guardClient.check(eq("system"), eq("doro"), eq("admin"), anyString())).thenReturn(true);
        Login a = signupAndLogin();
        jdbcTemplate.update("update users set role = 'ADMIN' where email = ?", a.email());
        String token = login(a.email()).accessToken();

        MvcResult created = mockMvc.perform(post(BASE).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("name", "Conf App", "redirectUris", List.of(GOOD_REDIRECT),
                        "confidential", true)))).andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(created.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode data = json(created).path("data");
        String clientId = data.path("clientId").asText();
        String secret = data.path("clientSecret").asText();
        assertThat(secret).startsWith("dcs_");
        assertThat(data.path("confidential").asBoolean()).isTrue();
        // DB 에는 평문이 없다
        String stored = jdbcTemplate.queryForObject("select client_secret_hash from oauth_clients where client_id = ?", String.class, clientId);
        assertThat(stored).isEqualTo(ClientSecrets.hash(secret)).doesNotContain(secret);

        String listBody = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(BASE)
                .header("Authorization", "Bearer " + token)).andReturn().getResponse().getContentAsString();
        assertThat(listBody).doesNotContain(secret).doesNotContain("clientSecret");

        MvcResult rotated = mockMvc.perform(post(BASE + "/" + clientId + "/secret")
                .header("Authorization", "Bearer " + token)).andReturn();
        assertThat(rotated.getResponse().getStatus()).isEqualTo(200);
        String newSecret = json(rotated).path("data").path("clientSecret").asText();
        assertThat(newSecret).isNotEqualTo(secret);

        Pkce p1 = newPkce();
        assertThat(mockMvc.perform(codeExchangeForm(freshCode(clientId, p1), clientId, GOOD_REDIRECT, p1.verifier())
                .param("client_secret", secret)).andReturn().getResponse().getStatus()).as("이전 시크릿 무효").isEqualTo(401);
        Pkce p2 = newPkce();
        assertThat(mockMvc.perform(codeExchangeForm(freshCode(clientId, p2), clientId, GOOD_REDIRECT, p2.verifier())
                .param("client_secret", newSecret)).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("시크릿 회전은 일반 사용자에게 403, 없는 클라이언트는 404")
    void rotateAuthorization() throws Exception {
        String userToken = signupAndLogin().accessToken();
        assertThat(mockMvc.perform(post(BASE + "/x/secret").header("Authorization", "Bearer " + userToken))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        when(guardClient.check(eq("system"), eq("doro"), eq("admin"), anyString())).thenReturn(true);
        Login a = signupAndLogin();
        jdbcTemplate.update("update users set role = 'ADMIN' where email = ?", a.email());
        String adminToken = login(a.email()).accessToken();
        assertThat(mockMvc.perform(post(BASE + "/no-such-client/secret").header("Authorization", "Bearer " + adminToken))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }
}

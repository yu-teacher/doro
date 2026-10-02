package com.hunnit_beasts.auth.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.hunnit_beasts.auth.infrastructure.guard.GuardClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 관리자 클라이언트 API: 인가(익명 401 / USER 403 / ADMIN 이어도 Guard 거부 시 403)와 redirect_uri 검증. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OAuthClientAdminApiTest extends OAuthTestSupport {

    private static final String BASE = "/api/v1/admin/oauth/clients";

    @MockitoBean
    private GuardClient guardClient;

    private String loginWithRole(String role) throws Exception {
        Login login = signupAndLogin();
        jdbcTemplate.update("update users set role = ? where email = ?", role, login.email());
        return login(login.email()).accessToken();
    }

    private String adminToken() throws Exception {
        when(guardClient.check(eq("system"), eq("doro"), eq("admin"), anyString())).thenReturn(true);
        return loginWithRole("ADMIN");
    }

    private String body(String name, List<String> uris) throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of("name", name, "redirectUris", uris));
    }

    private MvcResult create(String token, String json) throws Exception {
        return mockMvc.perform(post(BASE).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    @Test
    @DisplayName("익명은 401, 일반 사용자는 403 (생성/목록/삭제 모두)")
    void anonymousAndUserAreRejected() throws Exception {
        String content = body("App", List.of(GOOD_REDIRECT));
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(content)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mockMvc.perform(delete(BASE + "/x")).andExpect(status().isUnauthorized());

        String userToken = loginWithRole("USER");
        mockMvc.perform(post(BASE).header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON).content(content)).andExpect(status().isForbidden());
        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + userToken)).andExpect(status().isForbidden());
        mockMvc.perform(delete(BASE + "/x").header("Authorization", "Bearer " + userToken)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN 역할이어도 Guard 가 system:doro#admin 을 인정하지 않으면 403 (잘못된 본문이어도 검증 오류가 아닌 403)")
    void adminWithoutGuardGrantIsForbidden() throws Exception {
        when(guardClient.check(eq("system"), eq("doro"), eq("admin"), anyString())).thenReturn(false);
        String token = loginWithRole("ADMIN");

        assertThat(create(token, body("App", List.of(GOOD_REDIRECT))).getResponse().getStatus()).isEqualTo(403);
        assertThat(create(token, "{}").getResponse().getStatus()).isEqualTo(403);
        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mockMvc.perform(delete(BASE + "/x").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        assertThat(clientRepository.findAll()).noneMatch(c -> "Denied App".equals(c.getName()));
    }

    @Test
    @DisplayName("ADMIN + Guard 허용: client_id 자동 생성(url-safe), 목록 조회, 소프트 삭제")
    void adminLifecycle() throws Exception {
        String token = adminToken();

        MvcResult created = create(token, objectMapper.writeValueAsString(java.util.Map.of(
                "name", "Lifecycle App", "redirectUris", List.of(GOOD_REDIRECT, "http://localhost:3000/cb"), "scopes", List.of("openid", "email"))));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode data = json(created).path("data");
        String clientId = data.path("clientId").asText();
        assertThat(clientId).matches("^[A-Za-z0-9_-]{32}$");
        assertThat(data.path("active").asBoolean()).isTrue();
        assertThat(data.path("scopes")).hasSize(2);
        assertThat(data.path("redirectUris")).hasSize(2);

        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.clientId=='" + clientId + "')].name").value("Lifecycle App"));

        mockMvc.perform(delete(BASE + "/" + clientId).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data[?(@.clientId=='" + clientId + "')].active").value(false));
        // 행은 남아 있다(소프트 삭제)
        assertThat(clientRepository.findByClientId(clientId)).isPresent();
        assertThat(clientRepository.findByClientId(clientId).orElseThrow().isActive()).isFalse();

        mockMvc.perform(delete(BASE + "/no-such-client").header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("지정한 clientId 는 중복 등록할 수 없고(409) 형식이 잘못되면 400")
    void clientIdUniquenessAndFormat() throws Exception {
        String token = adminToken();
        String clientId = uniqueClientId();
        String content = objectMapper.writeValueAsString(java.util.Map.of("name", "A", "redirectUris", List.of(GOOD_REDIRECT), "clientId", clientId));
        assertThat(create(token, content).getResponse().getStatus()).isEqualTo(201);
        assertThat(create(token, content).getResponse().getStatus()).isEqualTo(409);

        String badId = objectMapper.writeValueAsString(java.util.Map.of("name", "A", "redirectUris", List.of(GOOD_REDIRECT), "clientId", "bad id"));
        assertThat(create(token, badId).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("redirect_uri 검증: http(비 loopback)/fragment/userinfo/와일드카드/상대경로/중복/개수·길이 초과는 400")
    void redirectUriValidation() throws Exception {
        String token = adminToken();
        List<String> invalid = List.of(
                "http://good.example/cb", "ftp://good.example/cb", "https://good.example/cb#frag", "https://user@good.example/cb",
                "https://*.good.example/cb", "https://good.example/*", "/relative/cb", "good.example/cb", "https://good.example/../cb",
                "https://good.example/cb with space", "javascript:alert(1)", "", "https:///cb");
        for (String uri : invalid) {
            assertThat(create(token, body("Bad", List.of(uri))).getResponse().getStatus()).as("uri=" + uri).isEqualTo(400);
        }
        assertThat(create(token, body("Dup", List.of(GOOD_REDIRECT, GOOD_REDIRECT))).getResponse().getStatus()).isEqualTo(400);
        assertThat(create(token, body("None", List.of())).getResponse().getStatus()).isEqualTo(400);
        assertThat(create(token, objectMapper.writeValueAsString(java.util.Map.of("name", "NoUris"))).getResponse().getStatus()).isEqualTo(400);

        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            tooMany.add("https://good.example/cb" + i);
        }
        assertThat(create(token, body("Many", tooMany)).getResponse().getStatus()).isEqualTo(400);
        assertThat(create(token, body("Long", List.of("https://good.example/" + "a".repeat(500)))).getResponse().getStatus()).isEqualTo(400);

        assertThat(create(token, objectMapper.writeValueAsString(java.util.Map.of(
                "name", "BadScope", "redirectUris", List.of(GOOD_REDIRECT), "scopes", List.of("admin")))).getResponse().getStatus()).isEqualTo(400);

        // 허용되는 형태: https, loopback 의 http (localhost / 127.0.0.1 / [::1]), 쿼리, 포트
        for (String ok : List.of("https://good.example:8443/cb?tenant=a", "http://localhost:3000/cb", "http://127.0.0.1:8080/cb", "http://[::1]:8080/cb")) {
            assertThat(create(token, body("Ok", List.of(ok))).getResponse().getStatus()).as("uri=" + ok).isEqualTo(201);
        }
    }

    @Test
    @DisplayName("10개 / 500자 경계값은 허용된다")
    void boundaryValuesAreAccepted() throws Exception {
        String token = adminToken();
        List<String> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ten.add("https://good.example/cb" + i);
        }
        assertThat(create(token, body("Ten", ten)).getResponse().getStatus()).isEqualTo(201);
        String longUri = "https://good.example/" + "a".repeat(500 - "https://good.example/".length());
        assertThat(longUri).hasSize(500);
        assertThat(create(token, body("Long", List.of(longUri))).getResponse().getStatus()).isEqualTo(201);
    }
}

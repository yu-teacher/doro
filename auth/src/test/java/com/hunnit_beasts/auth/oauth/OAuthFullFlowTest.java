package com.hunnit_beasts.auth.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.hunnit_beasts.auth.infrastructure.guard.GuardClient;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 클라이언트 등록 -> 브라우저 인가 요청(302) -> 로그인 -> Bearer 인가 -> 폼 토큰 교환 -> id_token 검증 -> 리프레시 -> userinfo. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OAuthFullFlowTest extends OAuthTestSupport {

    @MockitoBean
    private GuardClient guardClient;

    @Test
    @DisplayName("전체 흐름: 등록 → 302 → 로그인 → 인가 → 토큰 → id_token 검증 → 리프레시 → userinfo")
    void fullAuthorizationCodeFlow() throws Exception {
        // 1) 관리자가 클라이언트를 등록한다
        when(guardClient.check(eq("system"), eq("doro"), eq("admin"), anyString())).thenReturn(true);
        Login admin = signupAndLogin();
        jdbcTemplate.update("update users set role = 'ADMIN' where email = ?", admin.email());
        String adminToken = login(admin.email()).accessToken();
        MvcResult registered = mockMvc.perform(post("/api/v1/admin/oauth/clients").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Flow App", "redirectUris", List.of(GOOD_REDIRECT)))))
                .andExpect(status().isCreated()).andReturn();
        String clientId = json(registered).path("data").path("clientId").asText();

        // 2) 클라이언트 앱이 브라우저를 인가 엔드포인트로 보낸다(Bearer 없음) -> 동의 페이지로 302
        Pkce pkce = newPkce();
        String state = "csrf-state-123";
        String nonce = "nonce-abc";
        String query = "client_id=" + clientId + "&redirect_uri=" + UriUtils.encode(GOOD_REDIRECT, StandardCharsets.UTF_8)
                + "&response_type=code&scope=openid%20profile%20email&state=" + state + "&nonce=" + nonce
                + "&code_challenge=" + pkce.challenge() + "&code_challenge_method=S256";
        MvcResult redirect = mockMvc.perform(get(java.net.URI.create("/oauth2/authorize?" + query))).andExpect(status().isFound()).andReturn();
        String location = redirect.getResponse().getHeader("Location");
        assertThat(location).isEqualTo("/oauth2/consent?" + query);

        // 3) 사용자가 로그인하고, 동의 화면이 Location 의 쿼리를 그대로 Bearer JSON 모드로 호출한다
        Login user = signupAndLogin();
        Map<String, String> params = new java.util.LinkedHashMap<>();
        UriComponentsBuilder.fromUriString(location).build().getQueryParams().forEach((k, v) ->
                params.put(k, UriUtils.decode(v.get(0), StandardCharsets.UTF_8)));
        var authorize = get("/oauth2/authorize").header("Authorization", "Bearer " + user.accessToken());
        params.forEach(authorize::param);
        MvcResult authorized = mockMvc.perform(authorize).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value(state)).andReturn();
        String code = json(authorized).path("data").path("code").asText();
        assertThat(code).isNotBlank();

        // 4) 클라이언트가 폼으로 코드를 교환한다
        MvcResult tokenResult = mockMvc.perform(codeExchangeForm(code, clientId, GOOD_REDIRECT, pkce.verifier()))
                .andExpect(status().isOk()).andReturn();
        JsonNode tokens = json(tokenResult);
        assertThat(tokens.path("scope").asText()).isEqualTo("openid profile email");

        // 5) id_token 검증
        Claims claims = verifyIdToken(tokens.path("id_token").asText(), clientId).getPayload();
        assertThat(claims.getSubject()).isEqualTo(user.userId());
        assertThat(claims.get("nonce", String.class)).isEqualTo(nonce);
        assertThat(claims.get("email", String.class)).isEqualTo(user.email());

        // 6) 리프레시
        MvcResult refreshed = mockMvc.perform(tokenForm("grant_type", "refresh_token", "client_id", clientId,
                "refresh_token", tokens.path("refresh_token").asText())).andExpect(status().isOk()).andReturn();
        JsonNode refreshedTokens = json(refreshed);
        assertThat(refreshedTokens.path("refresh_token").asText()).isNotEqualTo(tokens.path("refresh_token").asText());

        // 7) userinfo (회전된 새 액세스 토큰으로)
        mockMvc.perform(get("/oauth2/userinfo").header("Authorization", "Bearer " + refreshedTokens.path("access_token").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sub").value(user.userId()))
                .andExpect(jsonPath("$.email").value(user.email()));
    }
}

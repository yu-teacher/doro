package com.hunnit_beasts.auth.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 토큰 엔드포인트: 폼/JSON 계약, 코드 1회성과 바인딩, 오류 형식. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OAuthTokenEndpointTest extends OAuthTestSupport {

    private static final String REDIRECT = "https://app-a.com/cb";

    private void assertInvalidGrant(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        JsonNode body = json(result);
        assertThat(body.path("error").asText()).isEqualTo("invalid_grant");
        assertThat(body.path("error_description").asText()).isNotBlank();
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    @DisplayName("폼(RFC 6749) 요청: snake_case 응답과 no-store/no-cache 헤더, 스코프 없으면 id_token/scope 생략")
    void formRequestReturnsRfcResponse() throws Exception {
        Login me = signupAndLogin();
        Pkce pkce = newPkce();
        String code = authorizeCode(me, "legacy-client", REDIRECT, pkce, null, null);

        mockMvc.perform(codeExchangeForm(code, "legacy-client", REDIRECT, pkce.verifier()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.refresh_token").isNotEmpty())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").isNumber())
                .andExpect(jsonPath("$.id_token").doesNotExist())
                .andExpect(jsonPath("$.scope").doesNotExist())
                .andExpect(jsonPath("$.success").doesNotExist());
    }

    @Test
    @DisplayName("JSON(camelCase) 요청은 기존 ApiResponse<TokenResponse> 계약을 유지하고 idToken/scope 는 null 이면 생략")
    void jsonRequestKeepsEnvelopeContract() throws Exception {
        Login me = signupAndLogin();
        Pkce pkce = newPkce();
        String code = authorizeCode(me, "legacy-client", REDIRECT, pkce, null, null);

        mockMvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantType\":\"authorization_code\",\"code\":\"" + code + "\",\"redirectUri\":\"" + REDIRECT
                                + "\",\"clientId\":\"legacy-client\",\"codeVerifier\":\"" + pkce.verifier() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.sessionId").isNotEmpty())
                .andExpect(jsonPath("$.data.idToken").doesNotExist())
                .andExpect(jsonPath("$.data.scope").doesNotExist());

        Pkce second = newPkce();
        String code2 = authorizeCode(me, "legacy-client", REDIRECT, second, "openid", "nn");
        mockMvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantType\":\"authorization_code\",\"code\":\"" + code2 + "\",\"redirectUri\":\"" + REDIRECT
                                + "\",\"clientId\":\"legacy-client\",\"codeVerifier\":\"" + second.verifier() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.idToken").isNotEmpty())
                .andExpect(jsonPath("$.data.scope").value("openid"));
    }

    @Test
    @DisplayName("인가 코드는 1회용: 재사용은 invalid_grant 이고 첫 교환으로 발급된 흐름은 영향받지 않는다")
    void codeIsSingleUse() throws Exception {
        Login me = signupAndLogin();
        Pkce pkce = newPkce();
        String code = authorizeCode(me, "legacy-client", REDIRECT, pkce, null, null);

        mockMvc.perform(codeExchangeForm(code, "legacy-client", REDIRECT, pkce.verifier())).andExpect(status().isOk());
        assertInvalidGrant(mockMvc.perform(codeExchangeForm(code, "legacy-client", REDIRECT, pkce.verifier())).andReturn());
    }

    @Test
    @DisplayName("다른 client_id / redirect_uri / code_verifier 는 모두 invalid_grant 이며 실패한 시도로 코드가 소비된다")
    void mismatchesAreInvalidGrantAndBurnTheCode() throws Exception {
        Login me = signupAndLogin();

        Pkce p1 = newPkce();
        String c1 = authorizeCode(me, "legacy-client", REDIRECT, p1, null, null);
        assertInvalidGrant(mockMvc.perform(codeExchangeForm(c1, "other-client", REDIRECT, p1.verifier())).andReturn());
        assertInvalidGrant(mockMvc.perform(codeExchangeForm(c1, "legacy-client", REDIRECT, p1.verifier())).andReturn());

        Pkce p2 = newPkce();
        String c2 = authorizeCode(me, "legacy-client", REDIRECT, p2, null, null);
        assertInvalidGrant(mockMvc.perform(codeExchangeForm(c2, "legacy-client", "https://app.doro.local/callback", p2.verifier())).andReturn());

        Pkce p3 = newPkce();
        String c3 = authorizeCode(me, "legacy-client", REDIRECT, p3, null, null);
        assertInvalidGrant(mockMvc.perform(codeExchangeForm(c3, "legacy-client", REDIRECT, newPkce().verifier())).andReturn());
        assertInvalidGrant(mockMvc.perform(codeExchangeForm(c3, "legacy-client", REDIRECT, p3.verifier())).andReturn());
    }

    @Test
    @DisplayName("코드는 code_challenge 에 바인딩된다: 다른 챌린지의 verifier 로는 교환할 수 없다")
    void codeIsBoundToChallenge() throws Exception {
        Login me = signupAndLogin();
        Pkce issuedFor = newPkce();
        Pkce attacker = newPkce();
        String code = authorizeCode(me, "legacy-client", REDIRECT, issuedFor, null, null);
        assertInvalidGrant(mockMvc.perform(codeExchangeForm(code, "legacy-client", REDIRECT, attacker.verifier())).andReturn());
    }

    @Test
    @DisplayName("형식이 잘못된 code_verifier(너무 짧음/허용되지 않는 문자)는 교환되지 않는다")
    void malformedVerifierIsRejected() throws Exception {
        Login me = signupAndLogin();
        Pkce pkce = newPkce();
        String code = authorizeCode(me, "legacy-client", REDIRECT, pkce, null, null);
        assertInvalidGrant(mockMvc.perform(codeExchangeForm(code, "legacy-client", REDIRECT, "short")).andReturn());
        String code2 = authorizeCode(me, "legacy-client", REDIRECT, pkce, null, null);
        assertInvalidGrant(mockMvc.perform(codeExchangeForm(code2, "legacy-client", REDIRECT, pkce.verifier() + "!!")).andReturn());
    }

    @Test
    @DisplayName("오류 형식: invalid_request / unsupported_grant_type / invalid_client(401) / invalid_scope")
    void errorFormats() throws Exception {
        // grant_type 누락 / 필수 파라미터 누락
        MvcResult noGrant = mockMvc.perform(tokenForm("code", "x")).andExpect(status().isBadRequest()).andReturn();
        assertThat(json(noGrant).path("error").asText()).isEqualTo("invalid_request");
        MvcResult noVerifier = mockMvc.perform(tokenForm("grant_type", "authorization_code", "code", "x",
                "client_id", "legacy-client", "redirect_uri", REDIRECT)).andExpect(status().isBadRequest()).andReturn();
        assertThat(json(noVerifier).path("error").asText()).isEqualTo("invalid_request");

        MvcResult unsupported = mockMvc.perform(tokenForm("grant_type", "password", "client_id", "legacy-client"))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(json(unsupported).path("error").asText()).isEqualTo("unsupported_grant_type");

        // 잘못된 client_id 형식은 invalid_request, 쿼리스트링에 실은 파라미터는 거부
        MvcResult badClient = mockMvc.perform(tokenForm("grant_type", "authorization_code", "code", "x", "client_id", "bad client!",
                "redirect_uri", REDIRECT, "code_verifier", newPkce().verifier())).andReturn();
        assertThat(badClient.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(badClient).path("error").asText()).isEqualTo("invalid_request");
        mockMvc.perform(post("/oauth2/token?code=leak").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    @DisplayName("JSON 요청의 오류는 기존 ErrorResponse 계약(400 INVALID_TOKEN 등)을 유지한다")
    void jsonErrorsKeepLegacyContract() throws Exception {
        mockMvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantType\":\"authorization_code\",\"code\":\"nope\",\"redirectUri\":\"" + REDIRECT
                                + "\",\"clientId\":\"legacy-client\",\"codeVerifier\":\"" + newPkce().verifier() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        mockMvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantType\":\"password\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mockMvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("승인된 스코프가 응답 scope 로 돌아오고, 코드에 바인딩된 스코프만 id_token 을 만든다")
    void grantedScopeIsReturned() throws Exception {
        String clientId = uniqueClientId();
        registerClient(clientId, List.of(GOOD_REDIRECT), "openid", "email");
        Login me = signupAndLogin();

        JsonNode body = exchange(me, clientId, GOOD_REDIRECT, "openid email", "n-1");
        assertThat(body.path("scope").asText()).isEqualTo("openid email");
        assertThat(body.path("id_token").asText()).isNotBlank();

        JsonNode noOpenid = exchange(me, clientId, GOOD_REDIRECT, "email", null);
        assertThat(noOpenid.path("scope").asText()).isEqualTo("email");
        assertThat(noOpenid.has("id_token")).isFalse();
    }
}

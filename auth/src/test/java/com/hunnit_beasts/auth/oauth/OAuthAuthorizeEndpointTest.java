package com.hunnit_beasts.auth.oauth;

import com.hunnit_beasts.auth.domain.oauth.entity.OAuthClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 인가 엔드포인트: redirect_uri 정확 일치, 오픈 리다이렉트 방지, 브라우저/JSON 두 모드, PKCE, 스코프. (레지스트리 모드 기본값 WARN) */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OAuthAuthorizeEndpointTest extends OAuthTestSupport {

    private static final String CHALLENGE = "E9Melhoa2OwvFrGMTJguCH5rtx64FIbEIqiPQsjzkxo";

    private static final List<String> BAD_REDIRECTS = List.of(
            "https://good.example/cb/",               // 끝 슬래시
            "https://GOOD.example/cb",                // 대소문자
            "https://good.example/CB",
            "https://good.example@evil.example",      // userinfo 위장
            "https://good.example@evil.example/cb",
            "https://good.example/cb#frag",           // fragment
            "https://good.example:8443/cb",           // 다른 포트
            "http://good.example/cb",                 // 다른 스킴
            "https://good.example/cb/../evil",        // 경로 탐색
            "https://good.example/cb/%2e%2e/evil",
            "https://good.example/cb?x=1",            // 쿼리 추가
            "https://evil.example/cb",
            "javascript:alert(1)",
            "https://good.example.evil.example/cb");

    private Map<String, String> queryOf(MvcResult result) {
        String location = result.getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        Map<String, String> decoded = new java.util.LinkedHashMap<>();
        UriComponentsBuilder.fromUriString(location).build().getQueryParams().forEach((k, v) ->
                decoded.put(k, UriUtils.decode(v.get(0), StandardCharsets.UTF_8)));
        return decoded;
    }

    @Test
    @DisplayName("등록 클라이언트: redirect_uri 는 정확히 일치해야 하며 변형은 Bearer/브라우저 모두 400 이고 어디로도 리다이렉트하지 않는다")
    void registeredClientRequiresExactRedirectMatch() throws Exception {
        String clientId = uniqueClientId();
        registerClient(clientId, List.of(GOOD_REDIRECT));
        Login me = signupAndLogin();

        mockMvc.perform(authorizeRequest(me, clientId, GOOD_REDIRECT, CHALLENGE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.code").isNotEmpty());

        for (String bad : BAD_REDIRECTS) {
            mockMvc.perform(authorizeRequest(me, clientId, bad, CHALLENGE))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().doesNotExist("Location"));
            MvcResult browser = mockMvc.perform(get("/oauth2/authorize").param("client_id", clientId)
                            .param("redirect_uri", bad).param("response_type", "code").param("code_challenge", CHALLENGE))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().doesNotExist("Location")).andReturn();
            assertThat(browser.getResponse().getContentAsString()).doesNotContain("evil.example");
        }
    }

    @Test
    @DisplayName("등록 클라이언트에는 전역 환경변수 허용 목록이 적용되지 않는다")
    void registeredClientIgnoresGlobalAllowlist() throws Exception {
        String clientId = uniqueClientId();
        registerClient(clientId, List.of(GOOD_REDIRECT));
        Login me = signupAndLogin();

        mockMvc.perform(authorizeRequest(me, clientId, "https://app-a.com/cb", CHALLENGE))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("미등록 client_id(WARN)는 환경변수 허용 목록으로 폴백하고, 목록 밖 redirect_uri 는 400")
    void unregisteredClientFallsBackToAllowlistInWarnMode() throws Exception {
        Login me = signupAndLogin();
        mockMvc.perform(authorizeRequest(me, "legacy-client", "https://app-a.com/cb", CHALLENGE))
                .andExpect(status().isOk());
        mockMvc.perform(authorizeRequest(me, "legacy-client", GOOD_REDIRECT, CHALLENGE))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("형식이 잘못된 client_id / 누락된 client_id·redirect_uri 는 400 이며 리다이렉트하지 않는다")
    void malformedClientIsRejectedWithoutRedirect() throws Exception {
        mockMvc.perform(get("/oauth2/authorize").param("client_id", "bad client!").param("redirect_uri", "https://app-a.com/cb")
                        .param("response_type", "code").param("code_challenge", CHALLENGE))
                .andExpect(status().isBadRequest()).andExpect(header().doesNotExist("Location"));
        mockMvc.perform(get("/oauth2/authorize").param("redirect_uri", "https://app-a.com/cb")
                        .param("response_type", "code").param("code_challenge", CHALLENGE))
                .andExpect(status().isBadRequest()).andExpect(header().doesNotExist("Location"));
        mockMvc.perform(get("/oauth2/authorize").param("client_id", "c")
                        .param("response_type", "code").param("code_challenge", CHALLENGE))
                .andExpect(status().isBadRequest()).andExpect(header().doesNotExist("Location"));
    }

    @Test
    @DisplayName("비활성화된 클라이언트는 환경변수 허용 목록으로 되살아나지 않고 거부된다")
    void deactivatedClientIsRejected() throws Exception {
        String clientId = uniqueClientId();
        OAuthClient client = registerClient(clientId, List.of("https://app-a.com/cb"));
        client.deactivate();
        clientRepository.save(client);
        Login me = signupAndLogin();

        mockMvc.perform(authorizeRequest(me, clientId, "https://app-a.com/cb", CHALLENGE))
                .andExpect(status().isBadRequest()).andExpect(header().doesNotExist("Location"));
    }

    @Test
    @DisplayName("Bearer JSON 모드: state 는 그대로 돌려주고 응답은 캐시되지 않는다")
    void jsonModeEchoesStateUnchanged() throws Exception {
        Login me = signupAndLogin();
        String state = "a b&c=d%25<script>/é";
        mockMvc.perform(authorizeRequest(me, "legacy-client", "https://app-a.com/cb", CHALLENGE).param("state", state))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.state").value(state))
                .andExpect(header().string("Cache-Control", "no-store"));
        mockMvc.perform(authorizeRequest(me, "legacy-client", "https://app-a.com/cb", CHALLENGE))
                .andExpect(jsonPath("$.data.state").value(""));
    }

    @Test
    @DisplayName("브라우저 모드: 유효한 요청은 원래 쿼리 문자열을 보존해 동의 페이지로 302 한다")
    void browserModeRedirectsToConsentPreservingQuery() throws Exception {
        String query = "client_id=legacy-client&redirect_uri=https%3A%2F%2Fapp-a.com%2Fcb&response_type=code"
                + "&code_challenge=" + CHALLENGE + "&scope=openid%20profile&state=xyz&nonce=n-1";
        mockMvc.perform(get(java.net.URI.create("/oauth2/authorize?" + query)))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/oauth2/consent?" + query))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    @DisplayName("브라우저 모드: client/redirect 검증 이후의 오류는 redirect_uri 로 error/error_description/state 를 붙여 302")
    void browserModeErrorsRedirectToRedirectUri() throws Exception {
        String state = "s t&a=t%e";
        MvcResult unsupported = mockMvc.perform(get("/oauth2/authorize").param("client_id", "legacy-client")
                        .param("redirect_uri", "https://app-a.com/cb").param("response_type", "token")
                        .param("code_challenge", CHALLENGE).param("state", state))
                .andExpect(status().isFound()).andReturn();
        Map<String, String> q = queryOf(unsupported);
        assertThat(unsupported.getResponse().getHeader("Location")).startsWith("https://app-a.com/cb?error=unsupported_response_type");
        assertThat(q).containsEntry("error", "unsupported_response_type").containsEntry("state", state);
        assertThat(q.get("error_description")).isNotBlank().doesNotContain("token");

        MvcResult plain = mockMvc.perform(get("/oauth2/authorize").param("client_id", "legacy-client")
                        .param("redirect_uri", "https://app-a.com/cb").param("response_type", "code")
                        .param("code_challenge", CHALLENGE).param("code_challenge_method", "plain").param("state", "S"))
                .andExpect(status().isFound()).andReturn();
        assertThat(queryOf(plain)).containsEntry("error", "invalid_request").containsEntry("state", "S");

        MvcResult badChallenge = mockMvc.perform(get("/oauth2/authorize").param("client_id", "legacy-client")
                        .param("redirect_uri", "https://app-a.com/cb").param("response_type", "code")
                        .param("code_challenge", "short"))
                .andExpect(status().isFound()).andReturn();
        assertThat(queryOf(badChallenge)).containsEntry("error", "invalid_request").doesNotContainKey("state");

        MvcResult missingChallenge = mockMvc.perform(get("/oauth2/authorize").param("client_id", "legacy-client")
                        .param("redirect_uri", "https://app-a.com/cb").param("response_type", "code"))
                .andExpect(status().isFound()).andReturn();
        assertThat(queryOf(missingChallenge)).containsEntry("error", "invalid_request");
    }

    @Test
    @DisplayName("redirect_uri 에 이미 쿼리가 있는 등록 클라이언트도 오류 리다이렉트는 & 로 이어 붙인다")
    void errorRedirectAppendsToExistingQuery() throws Exception {
        String clientId = uniqueClientId();
        registerClient(clientId, List.of("https://good.example/cb?tenant=a"));
        MvcResult result = mockMvc.perform(get("/oauth2/authorize").param("client_id", clientId)
                        .param("redirect_uri", "https://good.example/cb?tenant=a").param("response_type", "code")
                        .param("code_challenge", CHALLENGE).param("code_challenge_method", "plain"))
                .andExpect(status().isFound()).andReturn();
        assertThat(result.getResponse().getHeader("Location")).startsWith("https://good.example/cb?tenant=a&error=invalid_request");
    }

    @Test
    @DisplayName("Bearer JSON 모드에서 plain 방식/잘못된 response_type 은 400 JSON 오류(리다이렉트 없음)")
    void jsonModeRejectsPlainMethod() throws Exception {
        Login me = signupAndLogin();
        mockMvc.perform(authorizeRequest(me, "legacy-client", "https://app-a.com/cb", CHALLENGE).param("code_challenge_method", "plain"))
                .andExpect(status().isBadRequest()).andExpect(header().doesNotExist("Location"));
        mockMvc.perform(authorizeRequest(me, "legacy-client", "https://app-a.com/cb", CHALLENGE).param("code_challenge_method", "S256"))
                .andExpect(status().isOk());
        mockMvc.perform(authorizeRequest(me, "legacy-client", "https://app-a.com/cb", CHALLENGE).param("response_type", "token"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("요청 스코프는 클라이언트 allowed_scopes 의 부분집합이어야 하고, 지원하지 않는 스코프는 invalid_scope")
    void scopesMustBeSubsetOfClientScopes() throws Exception {
        String clientId = uniqueClientId();
        registerClient(clientId, List.of(GOOD_REDIRECT), "openid");
        Login me = signupAndLogin();

        mockMvc.perform(authorizeRequest(me, clientId, GOOD_REDIRECT, CHALLENGE).param("scope", "openid"))
                .andExpect(status().isOk());
        mockMvc.perform(authorizeRequest(me, clientId, GOOD_REDIRECT, CHALLENGE).param("scope", "openid profile"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(authorizeRequest(me, clientId, GOOD_REDIRECT, CHALLENGE).param("scope", "admin"))
                .andExpect(status().isBadRequest());
        // 브라우저 모드에서는 redirect_uri 로 invalid_scope
        MvcResult browser = mockMvc.perform(get("/oauth2/authorize").param("client_id", clientId)
                        .param("redirect_uri", GOOD_REDIRECT).param("response_type", "code")
                        .param("code_challenge", CHALLENGE).param("scope", "openid email").param("state", "S1"))
                .andExpect(status().isFound()).andReturn();
        assertThat(queryOf(browser)).containsEntry("error", "invalid_scope").containsEntry("state", "S1");
        // 스코프 생략은 허용(빈 스코프 → id_token 없음)
        mockMvc.perform(authorizeRequest(me, clientId, GOOD_REDIRECT, CHALLENGE)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Bearer 가 있으나 유효하지 않으면 401, 동의 화면 fetch 계약(JSON)은 그대로")
    void invalidBearerIs401() throws Exception {
        mockMvc.perform(get("/oauth2/authorize").header("Authorization", "Bearer garbage")
                        .param("client_id", "legacy-client").param("redirect_uri", "https://app-a.com/cb")
                        .param("response_type", "code").param("code_challenge", CHALLENGE))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/oauth2/authorize?client_id=legacy-client&redirect_uri=https://app-a.com/cb&response_type=code&code_challenge=" + CHALLENGE))
                .andExpect(status().isFound()).andExpect(header().string("Location", startsWith("/oauth2/consent?")));
    }
}

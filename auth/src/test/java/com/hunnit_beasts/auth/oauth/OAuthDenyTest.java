package com.hunnit_beasts.auth.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.hunnit_beasts.auth.infrastructure.guard.GuardClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 동의 화면의 "취소"는 앱에 error=access_denied 를 돌려보낼 주소를 서버에서 받는다(RFC 6749 §4.1.2.1).
 * 동의 화면의 쿼리는 누구나 만들 수 있으므로, 서버는 인가 요청과 같은 검증을 통과한 redirect_uri 로만 주소를 만들어 준다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OAuthDenyTest extends OAuthTestSupport {

    @MockitoBean
    private GuardClient guardClient;

    @Test
    @DisplayName("등록된 redirect_uri 면 access_denied 와 state 가 붙은 주소를 돌려준다")
    void returnsAccessDeniedRedirect() throws Exception {
        String clientId = uniqueClientId();
        registerClient(clientId, List.of(GOOD_REDIRECT));
        Login user = signupAndLogin();
        JsonNode body = json(mockMvc.perform(post("/oauth2/deny").header("Authorization", "Bearer " + user.accessToken())
                .param("client_id", clientId).param("redirect_uri", GOOD_REDIRECT).param("state", "abc 1"))
                .andExpect(status().isOk()).andReturn());
        URI target = URI.create(body.path("data").path("redirectUrl").asText());
        assertThat(target.getScheme() + "://" + target.getHost() + target.getPath()).isEqualTo(GOOD_REDIRECT);
        assertThat(target.getRawQuery()).contains("error=access_denied").contains("state=abc%201");
    }

    @Test
    @DisplayName("등록되지 않은 redirect_uri 로는 주소를 만들어 주지 않는다(오픈 리다이렉트 방지)")
    void refusesUnregisteredRedirect() throws Exception {
        String clientId = uniqueClientId();
        registerClient(clientId, List.of(GOOD_REDIRECT));
        Login user = signupAndLogin();
        String text = mockMvc.perform(post("/oauth2/deny").header("Authorization", "Bearer " + user.accessToken())
                .param("client_id", clientId).param("redirect_uri", "https://evil.example/cb").param("state", "s"))
                .andExpect(status().is4xxClientError()).andReturn().getResponse().getContentAsString();
        assertThat(text).doesNotContain("evil.example/cb?");
    }

    @Test
    @DisplayName("모르는 클라이언트나 로그인 없는 요청은 거부된다")
    void refusesUnknownClientAndAnonymous() throws Exception {
        Login user = signupAndLogin();
        mockMvc.perform(post("/oauth2/deny").header("Authorization", "Bearer " + user.accessToken())
                .param("client_id", "nobody").param("redirect_uri", GOOD_REDIRECT)).andExpect(status().is4xxClientError());
        String clientId = uniqueClientId();
        registerClient(clientId, List.of(GOOD_REDIRECT));
        mockMvc.perform(post("/oauth2/deny").param("client_id", clientId).param("redirect_uri", GOOD_REDIRECT))
                .andExpect(status().isUnauthorized());
    }
}

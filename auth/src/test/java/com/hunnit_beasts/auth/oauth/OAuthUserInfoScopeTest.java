package com.hunnit_beasts.auth.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.hunnit_beasts.auth.infrastructure.guard.GuardClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 외부(제3자) 앱이 받는 사용자 정보는 사용자가 허용한 scope 만큼이어야 한다: email scope 없이 이메일이 나가면 안 되고,
 * profile scope 없이 이름이 나가면 안 된다. 리프레시로 새 액세스 토큰을 받아도 같은 범위가 이어진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OAuthUserInfoScopeTest extends OAuthTestSupport {

    @MockitoBean
    private GuardClient guardClient;

    private JsonNode userinfo(String accessToken) throws Exception {
        return json(mockMvc.perform(get("/oauth2/userinfo").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk()).andReturn());
    }

    @Test
    @DisplayName("openid 만 허용하면 userinfo 는 sub 만 돌려준다(이메일·이름 없음)")
    void openidOnlyReturnsSubOnly() throws Exception {
        String clientId = uniqueClientId();
        registerClient(clientId, List.of(GOOD_REDIRECT), "openid", "profile", "email");
        Login user = signupAndLogin();
        JsonNode info = userinfo(exchange(user, clientId, GOOD_REDIRECT, "openid", "n").path("access_token").asText());
        assertThat(info.path("sub").asText()).isEqualTo(user.userId());
        assertThat(info.has("email")).isFalse();
        assertThat(info.has("email_verified")).isFalse();
        assertThat(info.has("name")).isFalse();
    }

    @Test
    @DisplayName("email 만 허용하면 이메일은 나오고 이름은 나오지 않는다")
    void emailScopeReturnsEmailOnly() throws Exception {
        String clientId = uniqueClientId();
        registerClient(clientId, List.of(GOOD_REDIRECT), "openid", "profile", "email");
        Login user = signupAndLogin();
        JsonNode info = userinfo(exchange(user, clientId, GOOD_REDIRECT, "openid email", "n").path("access_token").asText());
        assertThat(info.path("email").asText()).isEqualTo(user.email());
        assertThat(info.has("name")).isFalse();
    }

    @Test
    @DisplayName("profile 만 허용하면 이름은 나오고 이메일은 나오지 않는다")
    void profileScopeReturnsNameOnly() throws Exception {
        String clientId = uniqueClientId();
        registerClient(clientId, List.of(GOOD_REDIRECT), "openid", "profile", "email");
        Login user = signupAndLogin();
        JsonNode info = userinfo(exchange(user, clientId, GOOD_REDIRECT, "openid profile", "n").path("access_token").asText());
        assertThat(info.has("name")).isTrue();
        assertThat(info.has("email")).isFalse();
    }

    @Test
    @DisplayName("세 scope 를 모두 허용하면 전부 나온다")
    void allScopesReturnEverything() throws Exception {
        String clientId = uniqueClientId();
        registerClient(clientId, List.of(GOOD_REDIRECT), "openid", "profile", "email");
        Login user = signupAndLogin();
        JsonNode info = userinfo(exchange(user, clientId, GOOD_REDIRECT, "openid profile email", "n").path("access_token").asText());
        assertThat(info.path("email").asText()).isEqualTo(user.email());
        assertThat(info.has("name")).isTrue();
    }

    @Test
    @DisplayName("리프레시로 받은 새 액세스 토큰도 처음 허용한 범위를 그대로 따른다(넓어지지 않는다)")
    void refreshKeepsTheGrantedScope() throws Exception {
        String clientId = uniqueClientId();
        registerClient(clientId, List.of(GOOD_REDIRECT), "openid", "profile", "email");
        Login user = signupAndLogin();
        JsonNode first = exchange(user, clientId, GOOD_REDIRECT, "openid", "n");
        JsonNode refreshed = json(mockMvc.perform(tokenForm("grant_type", "refresh_token", "client_id", clientId,
                "refresh_token", first.path("refresh_token").asText())).andExpect(status().isOk()).andReturn());
        JsonNode info = userinfo(refreshed.path("access_token").asText());
        assertThat(info.has("email")).isFalse();
        assertThat(info.has("name")).isFalse();
    }
}

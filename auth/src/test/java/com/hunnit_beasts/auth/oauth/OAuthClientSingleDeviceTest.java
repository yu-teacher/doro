package com.hunnit_beasts.auth.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.hunnit_beasts.auth.core.redis.KillSwitchPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 상한을 1 로 두면 예전처럼 서비스당 하나만 유지한다(새 로그인이 이전 세션을 대체). */
@SpringBootTest(properties = "doro.iam.session.max-oauth-sessions-per-client=1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OAuthClientSingleDeviceTest extends OAuthTestSupport {

    @MockitoBean private KillSwitchPublisher killSwitch;

    @Test
    @DisplayName("max-oauth-sessions-per-client=1: 새 로그인이 같은 서비스의 이전 세션을 대체한다")
    void secondLoginReplacesTheFirst() throws Exception {
        String clientId = uniqueClientId();
        registerClient(clientId, List.of(GOOD_REDIRECT));
        Login me = signupAndLogin();

        JsonNode first = exchange(me, clientId, GOOD_REDIRECT, "openid", null);
        JsonNode second = exchange(me, clientId, GOOD_REDIRECT, "openid", null);

        int firstStatus = mockMvc.perform(tokenForm("grant_type", "refresh_token", "client_id", clientId,
                "refresh_token", first.path("refresh_token").asText())).andReturn().getResponse().getStatus();
        int secondStatus = mockMvc.perform(tokenForm("grant_type", "refresh_token", "client_id", clientId,
                "refresh_token", second.path("refresh_token").asText())).andReturn().getResponse().getStatus();
        assertThat(firstStatus).isEqualTo(400);
        assertThat(secondStatus).isEqualTo(200);
    }
}

package com.hunnit_beasts.auth.oauth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /oauth2/token 과 Bearer 없는 /oauth2/authorize 는 IP 단위 TOKEN 버킷을 공유해 제한된다. */
@SpringBootTest(properties = {
        "doro.iam.rate-limit.enabled=true",
        "doro.iam.rate-limit.token-max=3"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OAuthTokenRateLimitTest extends OAuthTestSupport {

    private int tokenCall(String ip) throws Exception {
        return mockMvc.perform(tokenForm("grant_type", "authorization_code").header("X-Real-IP", ip))
                .andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("토큰 엔드포인트: 같은 IP 가 한도를 넘으면 429 + Retry-After, 다른 IP 는 영향 없음")
    void tokenEndpointIsLimitedPerIp() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(tokenCall("203.0.113.50")).isEqualTo(400);
        }
        mockMvc.perform(tokenForm("grant_type", "authorization_code").header("X-Real-IP", "203.0.113.50"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        assertThat(tokenCall("203.0.113.51")).isEqualTo(400);
    }

    @Test
    @DisplayName("Bearer 없는 인가 요청은 같은 버킷으로 제한되고, Bearer 가 있는 요청은 제한되지 않는다")
    void anonymousAuthorizeIsLimited() throws Exception {
        Login me = signupAndLogin();
        String ip = "203.0.113.60";
        String url = "/oauth2/authorize?client_id=legacy-client&redirect_uri=https://app-a.com/cb&response_type=code"
                + "&code_challenge=E9Melhoa2OwvFrGMTJguCH5rtx64FIbEIqiPQsjzkxo";
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get(url).header("X-Real-IP", ip)).andExpect(status().isFound());
        }
        mockMvc.perform(get(url).header("X-Real-IP", ip)).andExpect(status().isTooManyRequests());

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(authorizeRequest(me, "legacy-client", "https://app-a.com/cb", "E9Melhoa2OwvFrGMTJguCH5rtx64FIbEIqiPQsjzkxo")
                    .header("X-Real-IP", ip)).andExpect(status().isOk());
        }
    }
}

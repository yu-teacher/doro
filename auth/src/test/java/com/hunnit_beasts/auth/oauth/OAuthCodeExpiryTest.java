package com.hunnit_beasts.auth.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;

/** 인가 코드 TTL(doro.oauth.code-ttl-seconds)을 1초로 낮춘 컨텍스트에서 만료된 코드가 교환되지 않음을 검증한다. */
@SpringBootTest(properties = "doro.oauth.code-ttl-seconds=1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OAuthCodeExpiryTest extends OAuthTestSupport {

    /** 테스트에서 실제 시간 경과를 기다린다(프로덕션 코드에는 sleep 이 없다). */
    private static final long WAIT_PAST_TTL_MILLIS = 1300;

    @Test
    @DisplayName("TTL 이 지난 인가 코드는 invalid_grant, TTL 안의 코드는 교환된다")
    void expiredCodeIsRejected() throws Exception {
        Login me = signupAndLogin();

        Pkce fresh = newPkce();
        String freshCode = authorizeCode(me, "legacy-client", "https://app-a.com/cb", fresh, null, null);
        MvcResult ok = mockMvc.perform(codeExchangeForm(freshCode, "legacy-client", "https://app-a.com/cb", fresh.verifier())).andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);

        Pkce stale = newPkce();
        String staleCode = authorizeCode(me, "legacy-client", "https://app-a.com/cb", stale, null, null);
        Thread.sleep(WAIT_PAST_TTL_MILLIS);
        MvcResult expired = mockMvc.perform(codeExchangeForm(staleCode, "legacy-client", "https://app-a.com/cb", stale.verifier())).andReturn();
        assertThat(expired.getResponse().getStatus()).isEqualTo(400);
        JsonNode body = json(expired);
        assertThat(body.path("error").asText()).isEqualTo("invalid_grant");
    }
}

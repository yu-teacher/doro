package com.hunnit_beasts.auth.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.hunnit_beasts.auth.core.redis.KillSwitchPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서비스(OAuth 클라이언트)별 기기 수 상한(기본 3): 같은 사용자가 한 서비스에 여러 기기로 로그인해 둘 수 있고, 넘으면 그 서비스에서
 * 가장 오래 쓰지 않은 세션만 종료된다. 다른 서비스와 포털 세션은 영향이 없다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OAuthClientDeviceLimitTest extends OAuthTestSupport {

    /** CI 에는 Redis 가 없으므로 세션 종료 통지는 가짜로 둔다. */
    @MockitoBean private KillSwitchPublisher killSwitch;

    private String clientId;

    private JsonNode loginDevice(Login me) throws Exception {
        return exchange(me, clientId, GOOD_REDIRECT, "openid", null);
    }

    private int refreshStatus(JsonNode tokens, String client) throws Exception {
        MvcResult r = mockMvc.perform(tokenForm("grant_type", "refresh_token", "client_id", client,
                "refresh_token", tokens.path("refresh_token").asText())).andReturn();
        return r.getResponse().getStatus();
    }

    /** 그 사용자의 이 클라이언트 OAuth 세션들을 만든 순서대로. */
    private List<String> sessionIds(Login me, String client) {
        return jdbcTemplate.queryForList(
                "select cast(id as varchar) from user_sessions where user_id = cast(? as uuid) and user_agent = ? and is_active = true order by created_at",
                String.class, me.userId(), "OAuth2 PKCE Flow: " + client);
    }

    private void setLastActive(String sessionId, Instant at) {
        jdbcTemplate.update("update user_sessions set last_active_at = ? where id = cast(? as uuid)", Timestamp.from(at), sessionId);
    }

    @Test
    @DisplayName("한 서비스에 3개 기기까지 동시에 로그인해 둘 수 있다")
    void threeDevicesCoexist() throws Exception {
        clientId = uniqueClientId();
        registerClient(clientId, List.of(GOOD_REDIRECT));
        Login me = signupAndLogin();

        JsonNode d1 = loginDevice(me);
        JsonNode d2 = loginDevice(me);
        JsonNode d3 = loginDevice(me);

        assertThat(sessionIds(me, clientId)).hasSize(3);
        assertThat(refreshStatus(d1, clientId)).as("첫 기기도 살아 있다").isEqualTo(200);
        assertThat(refreshStatus(d2, clientId)).isEqualTo(200);
        assertThat(refreshStatus(d3, clientId)).isEqualTo(200);
    }

    @Test
    @DisplayName("4번째 기기가 로그인하면 가장 오래 쓰지 않은 세션만 종료된다(만든 순서가 아니라 마지막 사용 기준)")
    void fourthDeviceEvictsTheLeastRecentlyUsed() throws Exception {
        clientId = uniqueClientId();
        registerClient(clientId, List.of(GOOD_REDIRECT));
        Login me = signupAndLogin();
        JsonNode d1 = loginDevice(me);
        JsonNode d2 = loginDevice(me);
        JsonNode d3 = loginDevice(me);
        List<String> ids = sessionIds(me, clientId);
        // 가장 먼저 만든 d1 을 가장 최근에 쓴 것으로, d2 를 가장 오래 쓰지 않은 것으로 만든다
        Instant now = Instant.now();
        setLastActive(ids.get(0), now);                       // d1: 방금 사용
        setLastActive(ids.get(1), now.minusSeconds(3600));    // d2: 1시간 전
        setLastActive(ids.get(2), now.minusSeconds(60));      // d3: 1분 전

        JsonNode d4 = loginDevice(me);

        assertThat(sessionIds(me, clientId)).as("여전히 3개").hasSize(3);
        assertThat(refreshStatus(d2, clientId)).as("가장 오래 쓰지 않은 d2 가 종료됐다").isEqualTo(400);
        assertThat(refreshStatus(d1, clientId)).as("처음 만들었지만 최근에 쓴 d1 은 유지").isEqualTo(200);
        assertThat(refreshStatus(d3, clientId)).isEqualTo(200);
        assertThat(refreshStatus(d4, clientId)).isEqualTo(200);
    }

    @Test
    @DisplayName("한 서비스가 상한에 닿아도 다른 서비스의 세션은 영향이 없다")
    void otherServicesAreIndependent() throws Exception {
        clientId = uniqueClientId();
        String other = uniqueClientId();
        registerClient(clientId, List.of(GOOD_REDIRECT));
        registerClient(other, List.of(GOOD_REDIRECT));
        Login me = signupAndLogin();
        JsonNode onOther = exchange(me, other, GOOD_REDIRECT, "openid", null);

        for (int i = 0; i < 5; i++) {
            loginDevice(me);
        }

        assertThat(sessionIds(me, clientId)).hasSize(3);
        assertThat(sessionIds(me, other)).as("다른 서비스의 세션은 그대로").hasSize(1);
        assertThat(refreshStatus(onOther, other)).isEqualTo(200);
    }

    @Test
    @DisplayName("포털 로그인 세션은 서비스 기기 수 상한의 영향을 받지 않는다")
    void portalSessionIsUnaffected() throws Exception {
        clientId = uniqueClientId();
        registerClient(clientId, List.of(GOOD_REDIRECT));
        Login me = signupAndLogin();

        for (int i = 0; i < 6; i++) {
            loginDevice(me);
        }

        Integer portalActive = jdbcTemplate.queryForObject(
                "select count(*) from user_sessions where user_id = cast(? as uuid) and is_active = true and (user_agent is null or user_agent not like 'OAuth2%')",
                Integer.class, me.userId());
        assertThat(portalActive).isGreaterThanOrEqualTo(1);
    }
}

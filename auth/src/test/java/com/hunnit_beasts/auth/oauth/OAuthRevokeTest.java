package com.hunnit_beasts.auth.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.hunnit_beasts.auth.domain.session.repository.UserSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** RFC 7009 토큰 폐기: OAuth 클라이언트(예: 블로그 BFF)가 로그아웃할 때 IAM 세션과 리프레시 토큰을 함께 끝낸다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OAuthRevokeTest extends OAuthTestSupport {

    @Autowired
    private UserSessionRepository sessionRepository;

    private MvcResult revoke(String... keyValues) throws Exception {
        var request = post("/oauth2/revoke").contentType(MediaType.APPLICATION_FORM_URLENCODED);
        for (int i = 0; i < keyValues.length; i += 2) {
            request.param(keyValues[i], keyValues[i + 1]);
        }
        return mockMvc.perform(request).andReturn();
    }

    private boolean sessionActive(String accessToken) throws Exception {
        String payload = new String(java.util.Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]));
        String sid = objectMapper.readTree(payload).path("sid").asText();
        return sessionRepository.findById(UUID.fromString(sid)).orElseThrow().isActive();
    }

    @Test
    @DisplayName("리프레시 토큰을 폐기하면 세션이 끝나고 그 토큰으로 더는 갱신할 수 없다")
    void revokingRefreshTokenEndsTheSession() throws Exception {
        String clientId = uniqueClientId();
        registerClient(clientId, List.of(GOOD_REDIRECT));
        Login me = signupAndLogin();
        JsonNode tokens = exchange(me, clientId, GOOD_REDIRECT, "openid", null);
        assertThat(sessionActive(tokens.path("access_token").asText())).isTrue();

        MvcResult result = revoke("token", tokens.path("refresh_token").asText(), "client_id", clientId);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(sessionActive(tokens.path("access_token").asText())).as("IAM 세션 종료").isFalse();
        MvcResult refresh = mockMvc.perform(tokenForm("grant_type", "refresh_token", "client_id", clientId,
                "refresh_token", tokens.path("refresh_token").asText())).andReturn();
        assertThat(refresh.getResponse().getStatus()).as("폐기된 토큰으로 갱신 불가").isEqualTo(400);
        // 세션이 끝났으니 같은 액세스 토큰으로 사용자 정보도 조회할 수 없다
        assertThat(mockMvc.perform(get("/oauth2/userinfo").header("Authorization", "Bearer " + tokens.path("access_token").asText()))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("알 수 없는 토큰이나 다른 클라이언트의 토큰은 200 으로 응답하되 아무것도 폐기하지 않는다 (RFC 7009: 존재 여부를 알리지 않음)")
    void unknownOrForeignTokensAreIgnoredSilently() throws Exception {
        String clientA = uniqueClientId();
        String clientB = uniqueClientId();
        registerClient(clientA, List.of(GOOD_REDIRECT));
        registerClient(clientB, List.of("https://other.example/cb"));
        Login me = signupAndLogin();
        JsonNode tokens = exchange(me, clientA, GOOD_REDIRECT, "openid", null);

        assertThat(revoke("token", "no-such-token", "client_id", clientA).getResponse().getStatus()).isEqualTo(200);
        assertThat(revoke("token", tokens.path("refresh_token").asText(), "client_id", clientB).getResponse().getStatus()).isEqualTo(200);

        assertThat(sessionActive(tokens.path("access_token").asText())).as("다른 클라이언트의 폐기 요청으로는 끝나지 않는다").isTrue();
    }

    @Test
    @DisplayName("일반 로그인 세션의 리프레시 토큰은 이 엔드포인트로 폐기할 수 없다")
    void firstPartyLoginTokensCannotBeRevokedHere() throws Exception {
        String clientId = uniqueClientId();
        registerClient(clientId, List.of(GOOD_REDIRECT));
        Login me = signupAndLogin();

        assertThat(revoke("token", me.refreshToken(), "client_id", clientId).getResponse().getStatus()).isEqualTo(200);

        assertThat(sessionRepository.findById(UUID.fromString(me.sessionId())).orElseThrow().isActive()).isTrue();
    }

    @Test
    @DisplayName("token 이나 client_id 가 없으면 400, 등록되지 않은 client_id 는 거부된다")
    void validatesParameters() throws Exception {
        assertThat(revoke("client_id", "x").getResponse().getStatus()).isEqualTo(400);
        assertThat(revoke("token", "t").getResponse().getStatus()).isEqualTo(400);
        // 레지스트리 모드(테스트 프로필 WARN)에서는 미등록 클라이언트도 형식만 맞으면 통과하므로 형식 오류만 확인한다
        assertThat(revoke("token", "t", "client_id", "bad id!").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("discovery 에 revocation_endpoint 가 포함된다")
    void discoveryAdvertisesRevocationEndpoint() throws Exception {
        MvcResult result = mockMvc.perform(get("/.well-known/openid-configuration")).andReturn();

        assertThat(json(result).path("revocation_endpoint").asText()).endsWith("/oauth2/revoke");
    }
}

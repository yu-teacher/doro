package com.hunnit_beasts.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SessionRevocationSecurityTest {

    private static final String PASSWORD = "Password123!";

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private record Login(String accessToken, String sessionId) {
    }

    private Login signupAndLogin(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"name\":\"Tester\"}"))
                .andExpect(status().isCreated());
        return login(email);
    }

    private Login login(String email) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"deviceInfo\":\"test\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode tokens = objectMapper.readTree(body).path("data").path("tokens");
        return new Login(tokens.path("accessToken").asText(), tokens.path("sessionId").asText());
    }

    private String uniqueEmail() {
        return "sec-" + UUID.randomUUID() + "@doro.local";
    }

    @Test
    @DisplayName("C-1: 인증 없는 로그아웃은 401 이고 세션은 유지된다")
    void logoutWithoutAuthIsRejected() throws Exception {
        Login victim = signupAndLogin(uniqueEmail());

        mockMvc.perform(post("/api/v1/auth/logout").param("sessionId", victim.sessionId()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/sessions").header("Authorization", "Bearer " + victim.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("C-1: 다른 사용자의 sessionId 로 로그아웃할 수 없다")
    void logoutOtherUsersSessionIsRejected() throws Exception {
        Login victim = signupAndLogin(uniqueEmail());
        Login attacker = signupAndLogin(uniqueEmail());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + attacker.accessToken())
                        .param("sessionId", victim.sessionId()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/sessions").header("Authorization", "Bearer " + victim.accessToken()))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("C-1: sessionId 없이도 토큰의 sid 로 본인 세션을 로그아웃한다")
    void logoutFallsBackToTokenSessionId() throws Exception {
        Login me = signupAndLogin(uniqueEmail());

        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + me.accessToken()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("C-2: 다른 사용자의 세션을 DELETE /sessions/{id} 로 종료할 수 없다")
    void deleteOtherUsersSessionIsRejected() throws Exception {
        Login victim = signupAndLogin(uniqueEmail());
        Login attacker = signupAndLogin(uniqueEmail());

        mockMvc.perform(delete("/api/v1/sessions/" + victim.sessionId())
                        .header("Authorization", "Bearer " + attacker.accessToken()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/sessions").header("Authorization", "Bearer " + victim.accessToken()))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("C-2: 인증 없는 세션 종료/다른 세션 일괄 종료는 401")
    void sessionRevocationRequiresLogin() throws Exception {
        Login victim = signupAndLogin(uniqueEmail());

        mockMvc.perform(delete("/api/v1/sessions/" + victim.sessionId())).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/sessions/revoke-others").param("currentSessionId", victim.sessionId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("C-2: revoke-others 는 본인의 다른 세션만 종료한다")
    void revokeOthersKeepsCurrentSession() throws Exception {
        String email = uniqueEmail();
        Login first = signupAndLogin(email);
        Login second = login(email);

        mockMvc.perform(post("/api/v1/sessions/revoke-others")
                        .header("Authorization", "Bearer " + second.accessToken())
                        .param("currentSessionId", second.sessionId()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/sessions").header("Authorization", "Bearer " + second.accessToken()))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].sessionId").value(second.sessionId()));
        // first 세션은 더 이상 활성이 아니다
        mockMvc.perform(delete("/api/v1/sessions/" + first.sessionId())
                        .header("Authorization", "Bearer " + second.accessToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("N7: 비밀번호 변경 시 현재 세션을 제외한 다른 세션이 종료된다")
    void passwordChangeRevokesOtherSessions() throws Exception {
        String email = uniqueEmail();
        Login first = signupAndLogin(email);
        Login second = login(email);

        mockMvc.perform(put("/api/v1/users/me/password")
                        .header("Authorization", "Bearer " + second.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"NewPassword456!\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/sessions").header("Authorization", "Bearer " + second.accessToken()))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].sessionId").value(second.sessionId()));
    }

    @Test
    @DisplayName("N1: Bearer 없는 /oauth2/authorize 는 코드를 발급하지 않고 동의 페이지로 302, 잘못된 Bearer 는 401 (계약 변경: 기존 401)")
    void authorizeRequiresLogin() throws Exception {
        mockMvc.perform(get("/oauth2/authorize?client_id=c&redirect_uri=https://app-a.com/cb&response_type=code"
                        + "&code_challenge=E9Melhoa2OwvFrGMTJguCH5rtx64FIbEIqiPQsjzkxo"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/oauth2/consent?")))
                .andExpect(content().string(not(containsString("code\":"))));
        mockMvc.perform(get("/oauth2/authorize").header("Authorization", "Bearer not-a-jwt")
                        .param("client_id", "c").param("redirect_uri", "https://app-a.com/cb")
                        .param("response_type", "code")
                        .param("code_challenge", "E9Melhoa2OwvFrGMTJguCH5rtx64FIbEIqiPQsjzkxo"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("N1: response_type 과 code_challenge 형식을 검증한다")
    void authorizeValidatesParameters() throws Exception {
        Login me = signupAndLogin(uniqueEmail());
        String challenge = "E9Melhoa2OwvFrGMTJguCH5rtx64FIbEIqiPQsjzkxo";

        mockMvc.perform(get("/oauth2/authorize").header("Authorization", "Bearer " + me.accessToken())
                        .param("client_id", "c").param("redirect_uri", "https://app-a.com/cb")
                        .param("response_type", "token").param("code_challenge", challenge))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/oauth2/authorize").header("Authorization", "Bearer " + me.accessToken())
                        .param("client_id", "c").param("redirect_uri", "https://app-a.com/cb")
                        .param("response_type", "code").param("code_challenge", "short"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/oauth2/authorize").header("Authorization", "Bearer " + me.accessToken())
                        .param("client_id", "c").param("redirect_uri", "https://app-a.com/cb")
                        .param("response_type", "code").param("code_challenge", challenge))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").isNotEmpty());
    }

    @Test
    @DisplayName("N11: 검증 실패 응답에 비밀번호 원문이 포함되지 않는다")
    void validationErrorDoesNotEchoPassword() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x@doro.local\",\"password\":\"short\",\"name\":\"Tester\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("short\""))))
                .andExpect(jsonPath("$.details[?(@.field=='password')].rejectedValue").value(org.hamcrest.Matchers.contains((Object) null)));
    }

    @Test
    @DisplayName("N11: 깨진 JSON 과 필수 파라미터 누락은 500 이 아니라 400")
    void malformedRequestsReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/auth/token/refresh").contentType(MediaType.APPLICATION_JSON).content(""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("OAuth: 허용 목록에 없거나 위험한 redirect_uri 로는 인가 코드를 발급하지 않는다")
    void authorizeRejectsRedirectUrisOutsideAllowlist() throws Exception {
        Login me = signupAndLogin(uniqueEmail());
        String challenge = "E9Melhoa2OwvFrGMTJguCH5rtx64FIbEIqiPQsjzkxo";

        for (String bad : new String[]{"https://evil.example/cb", "javascript:alert(1)", "https://app-a.com/cb/../evil", "https://app-a.com/cb?x=1", "https://user@app-a.com/cb"}) {
            mockMvc.perform(get("/oauth2/authorize").header("Authorization", "Bearer " + me.accessToken())
                            .param("client_id", "c").param("redirect_uri", bad)
                            .param("response_type", "code").param("code_challenge", challenge))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/oauth2/authorize").header("Authorization", "Bearer " + me.accessToken())
                        .param("client_id", "c").param("redirect_uri", "https://app-a.com/cb")
                        .param("response_type", "code").param("code_challenge", challenge))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").isNotEmpty());
    }

    @Test
    @DisplayName("존재하지 않는 경로 404, 지원하지 않는 메서드 405, 잘못된 Content-Type 415 (500 이 아니다)")
    void clientMistakesAreNot500() throws Exception {
        mockMvc.perform(get("/oauth2/no-such-endpoint")).andExpect(status().isNotFound());
        mockMvc.perform(post("/oauth2/authorize")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(post("/oauth2/token").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
    }
}

package com.hunnit_beasts.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunnit_beasts.auth.infrastructure.guard.GuardClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 계정 보안 보강: 비밀번호 재확인이 필요한 요청(비밀번호 변경, 2FA 등록)이 로그인과 같은 잠금 횟수를 공유하고,
 * 정지된 계정의 상태가 비밀번호 검증 전에 드러나지 않으며, 프로필 이미지 URL 이 허용 목록을 따른다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountSecurityHardeningTest {

    private static final String PASSWORD = "Password123!";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private GuardClient guardClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private record Account(String email, UUID id, String token) {
    }

    @BeforeEach
    void resetGuard() {
        reset(guardClient);
    }

    private Account account() throws Exception {
        String email = "hard-" + UUID.randomUUID() + "@doro.local";
        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"name\":\"Hardening\"}"))
                .andExpect(status().isCreated());
        UUID id = jdbc.queryForObject("select id from users where email = ?", UUID.class, email);
        return new Account(email, id, login(email, PASSWORD));
    }

    private String login(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("tokens").path("accessToken").asText();
    }

    private ResultActions loginAttempt(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private int failedAttempts(UUID userId) {
        return jdbc.queryForObject("select failed_attempts from credentials where user_id = ?", Integer.class, userId);
    }

    private ResultActions changePassword(Account a, String current, String next) throws Exception {
        return mockMvc.perform(put("/api/v1/users/me/password").header("Authorization", "Bearer " + a.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}"));
    }

    private ResultActions setup2fa(Account a, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/2fa/setup").header("Authorization", "Bearer " + a.token())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions setImage(Account a, String image) throws Exception {
        return mockMvc.perform(patch("/api/v1/users/me").header("Authorization", "Bearer " + a.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"profileImageUrl\":\"" + image + "\"}"));
    }

    // ---------------- A1: 비밀번호 변경 ----------------

    @Test
    @DisplayName("A1: 현재 비밀번호가 틀리면 실패 횟수가 늘고, 맞으면 0 으로 초기화된다")
    void wrongCurrentPasswordIsCounted() throws Exception {
        Account me = account();

        changePassword(me, "Wrong-Password1!", "NewPassword456!").andExpect(status().isUnauthorized());
        assertThat(failedAttempts(me.id())).isEqualTo(1);

        changePassword(me, PASSWORD, "NewPassword456!").andExpect(status().isOk());
        assertThat(failedAttempts(me.id())).isZero();
    }

    @Test
    @DisplayName("A1: 탈취된 토큰으로 현재 비밀번호를 무차별 대입하면 잠기고, 이후엔 맞는 비밀번호도 거부된다")
    void bruteForcingCurrentPasswordLocksTheAccount() throws Exception {
        Account me = account();

        for (int i = 0; i < 5; i++) {
            changePassword(me, "Wrong-Password" + i + "!", "NewPassword456!").andExpect(status().isUnauthorized());
        }

        changePassword(me, PASSWORD, "NewPassword456!")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
        // 비밀번호는 바뀌지 않았다: 잠금이 풀린 것으로 가정하고 직접 풀어도 옛 비밀번호가 그대로 유효하다
        jdbc.update("update credentials set locked_until = null, failed_attempts = 0 where user_id = ?", me.id());
        loginAttempt(me.email(), PASSWORD).andExpect(status().isOk());
    }

    // ---------------- A2: 2FA 등록 재확인 ----------------

    @Test
    @DisplayName("A2: 2FA 등록은 현재 비밀번호가 없으면 400, 틀리면 401 이고 실패가 합산된다")
    void totpSetupRequiresPassword() throws Exception {
        Account me = account();

        setup2fa(me, "{}").andExpect(status().isBadRequest());
        setup2fa(me, "{\"currentPassword\":\"Wrong-Password1!\"}").andExpect(status().isUnauthorized());
        assertThat(failedAttempts(me.id())).isEqualTo(1);
        // 시크릿이 대기 상태로도 저장되지 않았다
        assertThat(jdbc.queryForObject("select pending_totp_secret from credentials where user_id = ?", String.class, me.id()))
                .isNull();
    }

    @Test
    @DisplayName("A2: 올바른 비밀번호면 등록 정보를 받고 실패 횟수가 초기화된다")
    void totpSetupWithPasswordSucceeds() throws Exception {
        Account me = account();
        setup2fa(me, "{\"currentPassword\":\"Wrong-Password1!\"}").andExpect(status().isUnauthorized());

        setup2fa(me, "{\"currentPassword\":\"" + PASSWORD + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.secret").isNotEmpty());
        assertThat(failedAttempts(me.id())).isZero();
    }

    // ---------------- A3: 계정 상태 비노출 ----------------

    @Test
    @DisplayName("A3: 정지된 계정도 비밀번호가 틀리면 상태를 알려 주지 않고 일반 실패(401)만 돌려준다")
    void suspendedStatusIsHiddenBeforePasswordCheck() throws Exception {
        Account me = account();
        jdbc.update("update users set status = 'SUSPENDED' where id = ?", me.id());

        loginAttempt(me.email(), "Wrong-Password1!")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        assertThat(failedAttempts(me.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("A3: 정지된 계정이 올바른 비밀번호를 내면 그때서야 정지 사실을 알려 준다")
    void suspendedStatusIsRevealedAfterPasswordCheck() throws Exception {
        Account me = account();
        jdbc.update("update users set status = 'SUSPENDED' where id = ?", me.id());

        loginAttempt(me.email(), PASSWORD).andExpect(status().isForbidden());
        // 정지된 계정은 비밀번호가 맞아도 세션을 받지 못한다
        assertThat(jdbc.queryForObject("select count(*) from user_sessions where user_id = ? and is_active = true",
                Integer.class, me.id())).isEqualTo(1); // 가입 직후 로그인한 세션 하나(account())만 남아 있다
    }

    @Test
    @DisplayName("A3: 계정 조회(lookup)는 정지 여부로 응답이 달라지지 않는다")
    void lookupDoesNotRevealSuspension() throws Exception {
        Account me = account();
        jdbc.update("update users set status = 'SUSPENDED' where id = ?", me.id());

        mockMvc.perform(post("/api/v1/auth/lookup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + me.email() + "\"}"))
                .andExpect(status().isOk());
    }

    // ---------------- A4: 프로필 이미지 URL ----------------

    @Test
    @DisplayName("A4: 위험한 스킴(javascript:, data:text/html, http:)은 거부된다")
    void dangerousImageSchemesAreRejected() throws Exception {
        Account me = account();
        for (String bad : new String[]{"javascript:alert(1)", "data:text/html;base64,PHNjcmlwdD4=",
                "http://img.example/a.png", "vbscript:x", "//img.example/a.png", "file:///etc/passwd", "plain-text"}) {
            setImage(me, bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"));
        }
    }

    @Test
    @DisplayName("A4: https 와 이미지 data URL(포털의 기본 아바타 SVG 포함)은 허용된다")
    void allowedImageFormatsPass() throws Exception {
        Account me = account();
        for (String ok : new String[]{"https://img.example/a.png", "data:image/png;base64,AAAA",
                "data:image/jpeg;base64,AAAA", "data:image/webp;base64,AAAA",
                "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg'/>"}) {
            setImage(me, ok.replace("\"", "'")).andExpect(status().isOk());
        }
        setImage(me, "").andExpect(status().isOk());
    }

    // ---------------- A5: Swagger ----------------

    @Test
    @DisplayName("A5: Swagger UI 와 API 문서는 기본으로 꺼져 있어 익명 요청에 내용을 주지 않는다")
    void swaggerIsOffByDefault() throws Exception {
        for (String path : new String[]{"/v3/api-docs", "/swagger-ui.html", "/swagger-ui/index.html"}) {
            int code = mockMvc.perform(get(path)).andReturn().getResponse().getStatus();
            assertThat(code).as(path).isIn(401, 403, 404);
        }
    }
}

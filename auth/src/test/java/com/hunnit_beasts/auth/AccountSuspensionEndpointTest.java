package com.hunnit_beasts.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunnit_beasts.auth.infrastructure.guard.GuardClient;
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

import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 계정 정지·해제·잠금 해제 HTTP 엔드포인트: 인증·역할, 본문 검증, 정지 직후 이미 발급된 액세스 토큰의 효력. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountSuspensionEndpointTest {

    private static final String PASSWORD = "Password123!";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private GuardClient guardClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private record Account(UUID id, String email, String accessToken) { }

    private Account signupAndLogin(String role) throws Exception {
        String email = "susp-http-" + UUID.randomUUID() + "@doro.local";
        String signup = mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"name\":\"Susp Http\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(signup).path("data").path("userId").asText());
        jdbc.update("update users set role = ? where id = ?", role, id);
        return new Account(id, email, loginToken(email));
    }

    private String loginToken(String email) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("tokens").path("accessToken").asText();
    }

    private static String bearer(Account a) {
        return "Bearer " + a.accessToken();
    }

    private void guardGrantsAdmin() {
        when(guardClient.check(eq("system"), eq("doro"), eq("admin"), anyString())).thenReturn(true);
    }

    @Test
    @DisplayName("로그인하지 않으면 401, 일반 사용자는 403")
    void authenticationAndRole() throws Exception {
        Account user = signupAndLogin("USER");
        Account target = signupAndLogin("USER");
        String url = "/api/v1/admin/users/" + target.id() + "/suspension";

        mockMvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}")).andExpect(status().isUnauthorized());
        mockMvc.perform(put(url).header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(url).header("Authorization", bearer(user))).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/admin/users/" + target.id() + "/lock").header("Authorization", bearer(user))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("정지하면 대상이 이미 가진 액세스 토큰도 곧바로 쓸 수 없고, 로그인은 403(AUTH_40302)이며, 해제하면 다시 로그인된다")
    void suspendInvalidatesExistingTokenAndLogin() throws Exception {
        guardGrantsAdmin();
        Account admin = signupAndLogin("ADMIN");
        Account target = signupAndLogin("USER");
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", bearer(target))).andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/admin/users/" + target.id() + "/suspension").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"스팸\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.data.suspensionReason").value("스팸"));

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", bearer(target))).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + target.email() + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));

        mockMvc.perform(delete("/api/v1/admin/users/" + target.id() + "/suspension").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        String fresh = loginToken(target.email());
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + fresh)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("사유가 없거나 본문이 없으면 400, 대상이 없으면 404, 정지 중이 아닌 탈퇴 유예 계정 해제는 409")
    void validationAndErrors() throws Exception {
        guardGrantsAdmin();
        Account admin = signupAndLogin("ADMIN");
        Account target = signupAndLogin("USER");
        String url = "/api/v1/admin/users/" + target.id() + "/suspension";

        mockMvc.perform(put(url).header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put(url).header("Authorization", bearer(admin))).andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/admin/users/" + UUID.randomUUID() + "/suspension").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isNotFound());
        jdbc.update("update users set status = 'PENDING_DELETION' where id = ?", target.id());
        mockMvc.perform(delete(url).header("Authorization", bearer(admin))).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("본인 정지는 400, 최고 관리자 대상은 403")
    void selfAndSuperAdminAreProtected() throws Exception {
        guardGrantsAdmin();
        when(guardClient.check(eq("system"), eq("doro"), eq("manage_roles"), anyString())).thenReturn(true);
        Account admin = signupAndLogin("SUPER_ADMIN");
        Account other = signupAndLogin("SUPER_ADMIN");

        mockMvc.perform(put("/api/v1/admin/users/" + admin.id() + "/suspension").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/admin/users/" + other.id() + "/suspension").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("잠금 해제 엔드포인트는 비밀번호 실패 잠금을 풀고 갱신된 사용자 정보를 돌려준다")
    void unlockEndpoint() throws Exception {
        guardGrantsAdmin();
        Account admin = signupAndLogin("ADMIN");
        Account target = signupAndLogin("USER");
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + target.email() + "\",\"password\":\"Wrong-" + i + "-Password1!\"}"));
        }
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + target.email() + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

        mockMvc.perform(delete("/api/v1/admin/users/" + target.id() + "/lock").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.locked").value(false));
        loginToken(target.email());
    }
}

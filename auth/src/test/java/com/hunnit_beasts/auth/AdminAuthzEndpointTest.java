package com.hunnit_beasts.auth;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminAuthzEndpointTest {

    private static final String PASSWORD = "Password123!";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockitoBean
    private GuardClient guardClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String signupWithRoleAndLogin(String role) throws Exception {
        String email = "authz-" + UUID.randomUUID() + "@doro.local";
        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"name\":\"Authz\"}"))
                .andExpect(status().isCreated());
        jdbcTemplate.update("update users set role = ? where email = ?", role, email);
        String body = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("tokens").path("accessToken").asText();
    }

    @Test
    @DisplayName("로그인하지 않으면 401")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/admin/authz")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("일반 사용자는 403")
    void regularUserIsForbidden() throws Exception {
        String token = signupWithRoleAndLogin("USER");
        mockMvc.perform(get("/api/v1/admin/authz").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN 이어도 Guard 가 admin 으로 인정하지 않으면 403 (역할 클레임만으로 통과시키지 않는다)")
    void adminRoleWithoutGuardGrantIsForbidden() throws Exception {
        String token = signupWithRoleAndLogin("ADMIN");
        when(guardClient.check(eq("system"), eq("doro"), eq("admin"), anyString())).thenReturn(false);

        mockMvc.perform(get("/api/v1/admin/authz").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN 이고 Guard 도 인정하면 204")
    void adminWithGuardGrantIsAllowed() throws Exception {
        String token = signupWithRoleAndLogin("ADMIN");
        when(guardClient.check(eq("system"), eq("doro"), eq("admin"), anyString())).thenReturn(true);

        mockMvc.perform(get("/api/v1/admin/authz").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }
}

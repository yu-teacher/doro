package com.hunnit_beasts.auth;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 서브 서비스(블로그 API 키 등)가 계정 상태를 확인하는 내부 API: 서비스 토큰 필수, 게이트웨이를 거친 요청은 없는 경로, 상태 그대로 반영. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InternalUserStatusTest {

    private static final String TOKEN = "test-only-internal-token-0123456789abcdef0123456789abcdef";
    private static final String HEADER = "X-Doro-Service-Token";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private GuardClient guardClient;

    private UUID newUser() throws Exception {
        String email = "status-" + UUID.randomUUID() + "@doro.local";
        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"Password123!\",\"name\":\"Status User\"}"));
        return UUID.fromString(jdbc.queryForObject("select cast(id as varchar) from users where email = ?", String.class, email));
    }

    @Test
    @DisplayName("정상 계정은 ACTIVE, 정지·탈퇴 유예·영구 탈퇴는 그 상태를 그대로 돌려준다(정지가 풀리면 다시 ACTIVE)")
    void reflectsTheAccountState() throws Exception {
        UUID id = newUser();
        mockMvc.perform(get("/internal/v1/users/" + id + "/status").header(HEADER, TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.userId").value(id.toString()));

        for (String state : new String[]{"SUSPENDED", "PENDING_DELETION", "DELETED"}) {
            jdbc.update("update users set status = ? where id = ?", state, id);
            mockMvc.perform(get("/internal/v1/users/" + id + "/status").header(HEADER, TOKEN))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value(state));
        }
        jdbc.update("update users set status = 'ACTIVE' where id = ?", id);
        mockMvc.perform(get("/internal/v1/users/" + id + "/status").header(HEADER, TOKEN))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("없는 사용자는 404")
    void unknownUserIs404() throws Exception {
        mockMvc.perform(get("/internal/v1/users/" + UUID.randomUUID() + "/status").header(HEADER, TOKEN)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("서비스 토큰이 없거나 틀리면 401 이고, 게이트웨이를 거친 요청(X-Forwarded-For/X-Real-IP)은 없는 경로처럼 404")
    void requiresTheServiceTokenAndHidesFromTheGateway() throws Exception {
        UUID id = newUser();
        String url = "/internal/v1/users/" + id + "/status";

        mockMvc.perform(get(url)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(url).header(HEADER, "wrong-token")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(url).header(HEADER, TOKEN).header("X-Forwarded-For", "203.0.113.9")).andExpect(status().isNotFound());
        mockMvc.perform(get(url).header(HEADER, TOKEN).header("X-Real-IP", "203.0.113.9")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("응답에 이메일·이름 같은 개인정보가 없다")
    void noPersonalData() throws Exception {
        UUID id = newUser();
        String body = mockMvc.perform(get("/internal/v1/users/" + id + "/status").header(HEADER, TOKEN))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("@doro.local").doesNotContain("Status User");
    }
}

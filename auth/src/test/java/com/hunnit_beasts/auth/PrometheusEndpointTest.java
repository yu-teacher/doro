package com.hunnit_beasts.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A8c: /actuator/prometheus 는 ADMIN 에게만 노출된다. (테스트 컨텍스트는 기본적으로 메트릭 export 가 꺼져 있어 명시적으로 켠다) */
@SpringBootTest(properties = "management.prometheus.metrics.export.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PrometheusEndpointTest {

    private static final String PASSWORD = "Password123!";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String tokenWithRole(String role) throws Exception {
        String email = "prom-" + UUID.randomUUID() + "@doro.local";
        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"name\":\"Prom\"}"))
                .andExpect(status().isCreated());
        jdbcTemplate.update("update users set role = ? where email = ?", role, email);
        String body = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("tokens").path("accessToken").asText();
    }

    @Test
    @DisplayName("A8c: ADMIN 토큰은 /actuator/prometheus 200")
    void adminGetsPrometheus() throws Exception {
        mockMvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + tokenWithRole("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("A8c: 익명은 401, 일반 사용자는 403")
    void anonymousAndUserAreRejected() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + tokenWithRole("USER")))
                .andExpect(status().isForbidden());
    }
}

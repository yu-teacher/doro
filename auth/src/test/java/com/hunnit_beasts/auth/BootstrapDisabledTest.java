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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 토큰을 설정하지 않으면(기본) 부트스트랩 기능이 없는 것처럼 404 이고, 짧은 토큰도 기능을 켜지 않는다. */
class BootstrapDisabledTest {

    abstract static class Base {
        @Autowired MockMvc mockMvc;
        @Autowired JdbcTemplate jdbc;
        @MockitoBean GuardClient guardClient;
        final ObjectMapper objectMapper = new ObjectMapper();

        int claimAsNewUser(String token) throws Exception {
            String email = "bootoff-" + UUID.randomUUID() + "@doro.local";
            mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\",\"password\":\"Password123!\",\"name\":\"Boot Off\"}"));
            String body = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\",\"password\":\"Password123!\",\"deviceInfo\":\"t\"}"))
                    .andReturn().getResponse().getContentAsString();
            String access = objectMapper.readTree(body).path("data").path("tokens").path("accessToken").asText();
            int status = mockMvc.perform(post("/api/v1/auth/bootstrap").header("Authorization", "Bearer " + access)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}"))
                    .andReturn().getResponse().getStatus();
            String role = jdbc.queryForObject("select role from users where email = ?", String.class, email);
            assertThat(role).isEqualTo("USER");
            return status;
        }
    }

    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    static class NoToken extends Base {
        @Test
        @DisplayName("토큰 미설정: 어떤 값을 보내도 404, 역할은 그대로")
        void disabledByDefault() throws Exception {
            assertThat(claimAsNewUser("anything-at-all-0123456789abcdef0123")).isEqualTo(404);
        }
    }

    @SpringBootTest(properties = "doro.iam.bootstrap.token=too-short")
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    static class ShortToken extends Base {
        @Test
        @DisplayName("32자 미만 토큰은 기능을 켜지 않는다(맞는 값을 보내도 404)")
        void shortTokenDoesNotEnable() throws Exception {
            assertThat(claimAsNewUser("too-short")).isEqualTo(404);
        }
    }
}

package com.hunnit_beasts.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 인증 요청 DTO 의 문자열 길이 상한: 초과하면 DB 오류(500) 가 아니라 표준 400 검증 오류여야 한다. */
@SpringBootTest(properties = "doro.iam.rate-limit.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InputLengthValidationTest {

    @Autowired
    private MockMvc mockMvc;

    private void expectInvalid(String path, String json, String field) throws Exception {
        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"))
                .andExpect(jsonPath("$.details[?(@.field=='" + field + "')]").exists());
    }

    @Test
    @DisplayName("로그인: deviceInfo 255자 초과, password 128자 초과, email 255자 초과는 400")
    void loginBounds() throws Exception {
        String longEmail = "a".repeat(250) + "@doro.test";
        expectInvalid("/api/v1/auth/login",
                "{\"email\":\"a@doro.test\",\"password\":\"Password123!\",\"deviceInfo\":\"" + "d".repeat(256) + "\"}", "deviceInfo");
        expectInvalid("/api/v1/auth/login",
                "{\"email\":\"a@doro.test\",\"password\":\"" + "p".repeat(129) + "\"}", "password");
        expectInvalid("/api/v1/auth/login",
                "{\"email\":\"" + longEmail + "\",\"password\":\"Password123!\"}", "email");
    }

    @Test
    @DisplayName("로그인: 상한 이내(255/128자)는 길이 오류가 아니다(인증 실패는 400 이 아님)")
    void loginAtBoundaryPassesValidation() throws Exception {
        String result = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@doro.test\",\"password\":\"" + "p".repeat(128) + "\",\"deviceInfo\":\""
                                + "d".repeat(255) + "\"}"))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(result).doesNotContain("INVALID_INPUT_VALUE");
    }

    @Test
    @DisplayName("회원가입·계정 조회: email 255자 초과는 400")
    void emailBounds() throws Exception {
        String longEmail = "a".repeat(250) + "@doro.test";
        expectInvalid("/api/v1/auth/signup",
                "{\"email\":\"" + longEmail + "\",\"password\":\"Password123!\",\"name\":\"tester\"}", "email");
        expectInvalid("/api/v1/auth/lookup", "{\"email\":\"" + longEmail + "\"}", "email");
    }

    @Test
    @DisplayName("2FA 로그인: deviceInfo 255자 초과, tempTicket 512자 초과는 400")
    void totpLoginBounds() throws Exception {
        expectInvalid("/api/v1/auth/2fa/login",
                "{\"tempTicket\":\"t\",\"code\":\"123456\",\"deviceInfo\":\"" + "d".repeat(256) + "\"}", "deviceInfo");
        expectInvalid("/api/v1/auth/2fa/login",
                "{\"tempTicket\":\"" + "t".repeat(513) + "\",\"code\":\"123456\"}", "tempTicket");
    }
}

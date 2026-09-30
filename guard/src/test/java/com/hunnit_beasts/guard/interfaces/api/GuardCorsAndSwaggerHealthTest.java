package com.hunnit_beasts.guard.interfaces.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GuardCorsAndSwaggerHealthTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Guard 는 브라우저용 CORS 를 허용하지 않는다: preflight 응답에 Access-Control-Allow-Origin 이 없다")
    void testCorsIsNotEnabled() throws Exception {
        mockMvc.perform(options("/api/v1/guard/check")
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("Guard Swagger OpenAPI 3.0 명세 검증: /v3/api-docs 호출 시 정상 OpenAPI 스펙 반환")
    void testSwaggerApiDocsEndpoint() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.info.title").value("Doro Guard (Zanzibar ReBAC) API Documentation"));
    }

    @Test
    @DisplayName("Guard Actuator 헬스체크 검증: /actuator/health 호출 시 200 OK")
    void testActuatorHealthEndpoint() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists());
    }

    @Test
    @DisplayName("Guard: 잘못된 Content-Type 은 500 이 아니라 415, 없는 경로는 404")
    void clientMistakesAreNot500() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/guard/check")
                        .contentType("text/plain").content("x"))
                .andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(get("/api/v1/guard/nope")).andExpect(status().isNotFound());
    }
}

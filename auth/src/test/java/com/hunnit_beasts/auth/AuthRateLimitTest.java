package com.hunnit_beasts.auth;

import com.hunnit_beasts.auth.common.web.ClientIpResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "doro.iam.rate-limit.enabled=true",
        "doro.iam.rate-limit.login-max=3",
        "doro.iam.rate-limit.lookup-max=2"
})
class AuthRateLimitTest {

    @Autowired
    private MockMvc mockMvc;

    private int login(String ip) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Real-IP", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@doro.local\",\"password\":\"Password123!\"}"))
                .andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("같은 IP 의 로그인 시도가 한도를 넘으면 429 와 Retry-After 를 반환하고, 다른 IP 는 영향받지 않는다")
    void loginIsLimitedPerIp() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(login("203.0.113.10")).isNotEqualTo(429);
        }
        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Real-IP", "203.0.113.10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@doro.local\",\"password\":\"Password123!\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        assertThat(login("203.0.113.11")).isNotEqualTo(429);
    }

    @Test
    @DisplayName("계정 조회(lookup) 는 별도 버킷이라 로그인 한도의 영향을 받지 않는다")
    void lookupHasItsOwnBucket() throws Exception {
        for (int i = 0; i < 3; i++) {
            login("203.0.113.20");
        }
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/auth/lookup").header("X-Real-IP", "203.0.113.20")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@doro.local\"}"))
                    .andExpect(status().is(org.hamcrest.Matchers.not(429)));
        }
        mockMvc.perform(post("/api/v1/auth/lookup").header("X-Real-IP", "203.0.113.20")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@doro.local\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("사설망/루프백 프록시가 준 X-Real-IP 만 신뢰하고, 공인 IP 에서 온 요청의 헤더는 무시한다")
    void clientIpResolutionTrustsOnlyInternalProxies() {
        MockHttpServletRequest viaProxy = new MockHttpServletRequest();
        viaProxy.setRemoteAddr("172.18.0.5");
        viaProxy.addHeader("X-Real-IP", "198.51.100.7");
        assertThat(ClientIpResolver.resolve(viaProxy)).isEqualTo("198.51.100.7");

        MockHttpServletRequest spoofed = new MockHttpServletRequest();
        spoofed.setRemoteAddr("198.51.100.99");
        spoofed.addHeader("X-Real-IP", "1.2.3.4");
        assertThat(ClientIpResolver.resolve(spoofed)).isEqualTo("198.51.100.99");

        MockHttpServletRequest garbage = new MockHttpServletRequest();
        garbage.setRemoteAddr("127.0.0.1");
        garbage.addHeader("X-Real-IP", "not-an-ip; DROP TABLE");
        assertThat(ClientIpResolver.resolve(garbage)).isEqualTo("127.0.0.1");
    }
}

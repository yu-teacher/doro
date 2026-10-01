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

import java.util.List;

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

    private final ClientIpResolver resolver = new ClientIpResolver(List.of("127.0.0.0/8", "::1/128", "172.16.0.0/12"));

    private MockHttpServletRequest request(String remote, String realIpHeader) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remote);
        if (realIpHeader != null) {
            request.addHeader("X-Real-IP", realIpHeader);
        }
        return request;
    }

    @Test
    @DisplayName("신뢰 프록시(도커 브리지/루프백)가 준 X-Real-IP 만 사용한다")
    void trustedProxyHeaderIsHonoured() {
        assertThat(resolver.resolve(request("172.18.0.13", "198.51.100.7"))).isEqualTo("198.51.100.7");
        assertThat(resolver.resolve(request("127.0.0.1", "198.51.100.7"))).isEqualTo("198.51.100.7");
        assertThat(resolver.resolve(request("0:0:0:0:0:0:0:1", "198.51.100.7"))).isEqualTo("198.51.100.7");
    }

    @Test
    @DisplayName("LAN 기기와 인터넷 클라이언트가 보낸 X-Real-IP 는 위조일 수 있어 무시한다")
    void headersFromUntrustedPeersAreIgnored() {
        assertThat(resolver.resolve(request("192.168.0.25", "1.2.3.4"))).isEqualTo("192.168.0.25");
        assertThat(resolver.resolve(request("10.0.0.8", "1.2.3.4"))).isEqualTo("10.0.0.8");
        assertThat(resolver.resolve(request("198.51.100.99", "1.2.3.4"))).isEqualTo("198.51.100.99");
    }

    @Test
    @DisplayName("신뢰 프록시여도 IP 형식이 아닌 헤더 값은 무시한다")
    void malformedHeaderIsIgnored() {
        assertThat(resolver.resolve(request("172.18.0.13", "not-an-ip; DROP TABLE"))).isEqualTo("172.18.0.13");
        assertThat(resolver.resolve(request("172.18.0.13", null))).isEqualTo("172.18.0.13");
    }

    @Test
    @DisplayName("신뢰 프록시 목록은 설정으로 바꿀 수 있다")
    void trustedProxiesAreConfigurable() {
        ClientIpResolver lanProxy = new ClientIpResolver(List.of("192.168.0.2/32"));
        assertThat(lanProxy.resolve(request("192.168.0.2", "198.51.100.7"))).isEqualTo("198.51.100.7");
        assertThat(lanProxy.resolve(request("192.168.0.3", "198.51.100.7"))).isEqualTo("192.168.0.3");
    }
}

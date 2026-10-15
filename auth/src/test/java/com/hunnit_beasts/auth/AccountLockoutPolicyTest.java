package com.hunnit_beasts.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunnit_beasts.auth.core.redis.KillSwitchPublisher;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 계정 잠금 정책: 잠금은 (계정, IP) 단위가 기본이라 남이 내 계정을 잠글 수 없고, 여러 IP 가 합쳐 시간당 30회 넘게 틀릴 때만
 * 계정 전체가 잠긴다. 합의한 수치: 쌍 5회/15분 → 그 IP 만 15분 차단, 계정 30회/1시간 → 1시간 잠금.
 * (시간이 흐르는 부분은 LoginAttemptTrackerTest 가 가짜 시계로 시험한다.)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountLockoutPolicyTest {

    private static final String PASSWORD = "Password123!";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private GuardClient guardClient;
    /** CI 에는 Redis 가 없으므로 세션 종료 통지는 가짜로 둔다. */
    @MockitoBean private KillSwitchPublisher killSwitch;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String newUser() throws Exception {
        String email = "lockpol-" + UUID.randomUUID() + "@doro.local";
        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .header("X-Real-IP", "192.0.2.250")
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"name\":\"Lock Policy\"}"));
        return email;
    }

    /** 지정한 IP 에서 로그인을 시도하고 HTTP 상태를 돌려준다. */
    private int login(String email, String password, String ip) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Real-IP", ip)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"deviceInfo\":\"t\"}"))
                .andReturn().getResponse().getStatus();
    }

    private void fail(String email, String ip, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            assertThat(login(email, "wrong-password-" + i, ip)).as("틀린 비밀번호는 401").isEqualTo(401);
        }
    }

    @Test
    @DisplayName("공격자가 한 IP 에서 5번 틀려도 다른 IP 의 본인은 로그인된다 (남의 계정을 잠글 수 없다)")
    void foreignIpCannotLockTheVictimOut() throws Exception {
        String victim = newUser();
        fail(victim, "203.0.113.10", 5);

        assertThat(login(victim, PASSWORD, "198.51.100.7")).as("다른 IP 의 본인").isEqualTo(200);
    }

    @Test
    @DisplayName("5번 틀린 그 IP 는 올바른 비밀번호도 거절된다(403), 같은 IP 의 다른 계정은 영향이 없다")
    void lockedPairRejectsEvenTheCorrectPassword() throws Exception {
        String victim = newUser();
        String other = newUser();
        fail(victim, "203.0.113.20", 5);

        assertThat(login(victim, PASSWORD, "203.0.113.20")).isEqualTo(403);
        assertThat(login(other, PASSWORD, "203.0.113.20")).as("쌍 단위라 다른 계정은 그대로").isEqualTo(200);
    }

    @Test
    @DisplayName("성공하면 그 IP 의 실패 횟수가 0 으로 돌아간다")
    void successClearsThePairCounter() throws Exception {
        String user = newUser();
        fail(user, "203.0.113.30", 4);
        assertThat(login(user, PASSWORD, "203.0.113.30")).isEqualTo(200);

        fail(user, "203.0.113.30", 4);
        assertThat(login(user, PASSWORD, "203.0.113.30")).as("성공 뒤 다시 4번이라 잠기지 않았다").isEqualTo(200);
    }

    @Test
    @DisplayName("쌍이 잠긴 뒤의 시도는 계정 합산에 들지 않아, IP 하나로는 계정 전체를 잠글 수 없다")
    void oneIpCannotReachTheAccountThreshold() throws Exception {
        String victim = newUser();
        fail(victim, "203.0.113.40", 5);
        for (int i = 0; i < 25; i++) {
            login(victim, "wrong-" + i, "203.0.113.40");   // 이미 잠긴 쌍: 거절될 뿐 합산되지 않는다
        }

        assertThat(login(victim, PASSWORD, "198.51.100.8")).as("한 IP 의 폭주에도 본인은 로그인된다").isEqualTo(200);
    }

    @Test
    @DisplayName("여러 IP 가 합쳐 30번 틀리면 계정 전체가 잠기고, 관리자가 해제하면 다시 로그인된다")
    void distributedFailuresLockTheAccountAndAdminCanUnlock() throws Exception {
        String victim = newUser();
        for (int ip = 1; ip <= 7; ip++) {            // IP 당 4번(쌍 잠금 아래) × 7 = 28번
            fail(victim, "203.0.113." + (100 + ip), 4);
        }
        fail(victim, "203.0.113.108", 2);            // 합계 30번째 실패에서 계정 전체가 잠긴다

        assertThat(login(victim, PASSWORD, "198.51.100.9")).as("계정 전체 잠금").isEqualTo(403);

        when(guardClient.check(eq("system"), eq("doro"), eq("admin"), anyString())).thenReturn(true);
        String adminEmail = newUser();
        jdbc.update("update users set role = 'ADMIN' where email = ?", adminEmail);
        String adminToken = loginToken(adminEmail);
        String victimId = jdbc.queryForObject("select id from users where email = ?", String.class, victim);
        assertThat(mockMvc.perform(delete("/api/v1/admin/users/" + victimId + "/lock")
                .header("Authorization", "Bearer " + adminToken)).andReturn().getResponse().getStatus()).isEqualTo(200);

        assertThat(login(victim, PASSWORD, "198.51.100.9")).as("해제 뒤 본인").isEqualTo(200);
        assertThat(login(victim, PASSWORD, "203.0.113.101")).as("해제하면 쌍 카운터도 초기화된다").isEqualTo(200);
    }

    @Test
    @DisplayName("IPv6 는 /64 로 묶어 센다: 같은 /64 안에서 주소만 바꿔도 잠금을 피하지 못하고, 다른 /64 의 본인은 영향이 없다")
    void ipv6AddressesInTheSame64ShareOneLockout() throws Exception {
        String victim = newUser();
        for (int i = 1; i <= 5; i++) {
            assertThat(login(victim, "wrong-password-" + i, "2001:db8:abcd:12::" + i)).as("틀린 비밀번호는 401").isEqualTo(401);
        }

        assertThat(login(victim, PASSWORD, "2001:db8:abcd:12::99")).as("같은 /64 의 다른 주소도 잠긴다").isEqualTo(403);
        assertThat(login(victim, PASSWORD, "2001:db8:abcd:13::1")).as("다른 /64 의 본인").isEqualTo(200);
    }

    private String loginToken(String email) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Real-IP", "192.0.2.251")
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"deviceInfo\":\"t\"}"))
                .andReturn().getResponse().getContentAsString();
        JsonNode tokens = objectMapper.readTree(body).path("data").path("tokens");
        return tokens.path("accessToken").asText();
    }
}

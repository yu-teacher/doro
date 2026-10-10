package com.hunnit_beasts.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunnit_beasts.auth.core.redis.KillSwitchPublisher;
import com.hunnit_beasts.auth.infrastructure.guard.GuardClient;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 첫 관리자 부트스트랩(POST /api/v1/auth/bootstrap): 토큰이 켜져 있고 최고 관리자가 없을 때만, 토큰을 아는 로그인 사용자가 본인을 승격한다. */
@SpringBootTest(properties = "doro.iam.bootstrap.token=" + BootstrapTest.TOKEN)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BootstrapTest {

    static final String TOKEN = "test-bootstrap-token-0123456789abcdef0123";
    private static final String PASSWORD = "Password123!";
    private static final String URL = "/api/v1/auth/bootstrap";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private GuardClient guardClient;
    /** CI 에는 Redis 가 없으므로 세션 종료 통지는 가짜로 둔다. */
    @MockitoBean private KillSwitchPublisher killSwitch;

    private final ObjectMapper objectMapper = new ObjectMapper();

    record Account(String email, String userId, String accessToken) {}

    /** 같은 H2 DB 를 다른 테스트와 공유하므로 "최고 관리자 없음" 상태를 직접 만든다. */
    @BeforeEach
    void noSuperAdminExists() {
        reset(guardClient);
        jdbc.update("update users set role = 'USER' where role = 'SUPER_ADMIN'");
    }

    private Account newAccount() throws Exception {
        String email = "boot-" + UUID.randomUUID() + "@doro.local";
        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"name\":\"Boot\"}"));
        String body = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"deviceInfo\":\"t\"}"))
                .andReturn().getResponse().getContentAsString();
        JsonNode tokens = objectMapper.readTree(body).path("data").path("tokens");
        String id = jdbc.queryForObject("select id from users where email = ?", String.class, email);
        return new Account(email, id, tokens.path("accessToken").asText());
    }

    private int claim(Account who, String token) throws Exception {
        var req = post(URL).contentType(MediaType.APPLICATION_JSON)
                .content(token == null ? "{}" : "{\"token\":\"" + token + "\"}");
        if (who != null) {
            req.header("Authorization", "Bearer " + who.accessToken());
        }
        return mockMvc.perform(req).andReturn().getResponse().getStatus();
    }

    private String roleOf(Account a) {
        return jdbc.queryForObject("select role from users where id = ?", String.class, UUID.fromString(a.userId()));
    }

    @Test
    @DisplayName("로그인 없이는 401, 토큰이 없거나 틀리면 403 이고 역할은 그대로다")
    void rejectsAnonymousAndWrongToken() throws Exception {
        Account me = newAccount();
        assertThat(claim(null, TOKEN)).isEqualTo(401);
        assertThat(claim(me, null)).isEqualTo(403);
        assertThat(claim(me, "wrong")).isEqualTo(403);
        assertThat(claim(me, TOKEN + "x")).isEqualTo(403);
        assertThat(roleOf(me)).isEqualTo("USER");
    }

    @Test
    @DisplayName("맞는 토큰이면 본인이 SUPER_ADMIN 이 되고, Guard 튜플이 쓰이며, 기존 세션은 끝난다")
    void promotesTheCaller() throws Exception {
        Account me = newAccount();

        assertThat(claim(me, TOKEN)).isEqualTo(200);

        assertThat(roleOf(me)).isEqualTo("SUPER_ADMIN");
        verify(guardClient).writeTuplesOrThrow(anyList());
        // 기존 세션은 모두 끝난다(액세스 토큰 즉시 차단은 Redis 킬스위치 몫이라 여기선 DB 상태로 확인)
        Integer active = jdbc.queryForObject("select count(*) from user_sessions where user_id = ? and is_active = true",
                Integer.class, UUID.fromString(me.userId()));
        assertThat(active).isZero();
    }

    @Test
    @DisplayName("최고 관리자가 이미 있으면 맞는 토큰이어도 409 — 한 번 성공하면 다시 쓸 수 없다")
    void refusesWhenASuperAdminExists() throws Exception {
        Account first = newAccount();
        Account second = newAccount();
        assertThat(claim(first, TOKEN)).isEqualTo(200);

        assertThat(claim(second, TOKEN)).isEqualTo(409);
        assertThat(roleOf(second)).isEqualTo("USER");
    }

    @Test
    @DisplayName("탈퇴한 최고 관리자는 '있음'으로 세지 않는다")
    void deletedSuperAdminDoesNotCount() throws Exception {
        Account ghost = newAccount();
        jdbc.update("update users set role = 'SUPER_ADMIN', status = 'DELETED' where id = ?", UUID.fromString(ghost.userId()));
        Account me = newAccount();

        assertThat(claim(me, TOKEN)).isEqualTo(200);
        assertThat(roleOf(me)).isEqualTo("SUPER_ADMIN");
    }

    @Test
    @DisplayName("정상(ACTIVE)이 아닌 계정은 승격할 수 없다")
    void suspendedAccountIsRefused() throws Exception {
        Account me = newAccount();
        jdbc.update("update users set status = 'SUSPENDED' where id = ?", UUID.fromString(me.userId()));

        assertThat(claim(me, TOKEN)).isIn(401, 403, 409);
        assertThat(roleOf(me)).isEqualTo("USER");
    }

    @Test
    @DisplayName("Guard 동기화가 실패하면 503 이고 DB 의 역할도 바뀌지 않는다")
    void guardFailureRollsBack() throws Exception {
        Account me = newAccount();
        doThrow(new IllegalStateException("guard down")).when(guardClient).writeTuplesOrThrow(anyList());

        assertThat(claim(me, TOKEN)).isEqualTo(503);
        assertThat(roleOf(me)).isEqualTo("USER");
    }

    @Test
    @DisplayName("동시에 여러 명이 요청해도 최고 관리자는 정확히 한 명만 생긴다")
    void onlyOneWinsARace() throws Exception {
        int n = 6;
        List<Account> accounts = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            accounts.add(newAccount());
        }
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new java.util.ArrayList<>();
        for (Account a : accounts) {
            results.add(pool.submit(() -> {
                start.await();
                return claim(a, TOKEN);
            }));
        }
        start.countDown();
        int ok = 0;
        for (Future<Integer> f : results) {
            if (f.get() == 200) {
                ok++;
            }
        }
        pool.shutdown();

        assertThat(ok).isEqualTo(1);
        Integer supers = jdbc.queryForObject("select count(*) from users where role = 'SUPER_ADMIN'", Integer.class);
        assertThat(supers).isEqualTo(1);
    }
}

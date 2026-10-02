package com.hunnit_beasts.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunnit_beasts.auth.core.redis.KillSwitchPublisher;
import com.hunnit_beasts.auth.core.token.JwtKeyProvider;
import com.hunnit_beasts.auth.domain.session.entity.UserSession;
import com.hunnit_beasts.auth.domain.session.repository.UserSessionRepository;
import com.hunnit_beasts.auth.domain.user.entity.UserRole;
import com.hunnit_beasts.auth.domain.user.entity.UserStatus;
import com.hunnit_beasts.auth.domain.user.repository.UserRepository;
import com.hunnit_beasts.auth.domain.user.service.UserService;
import com.hunnit_beasts.auth.infrastructure.guard.GuardClient;
import io.jsonwebtoken.Jwts;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/v1/sessions/current: 서브 서비스 SDK 가 폐기된 세션을 감지하기 위한 DB 기준 생존 확인 엔드포인트. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CurrentSessionEndpointTest {

    private static final String PASSWORD = "Password123!";
    private static final String CURRENT = "/api/v1/sessions/current";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserSessionRepository sessionRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserService userService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JwtKeyProvider keyProvider;
    @MockitoBean
    private GuardClient guardClient;
    /** Redis 블랙리스트가 비어 있는(또는 Redis 장애로 fail-open 된) 상황을 결정적으로 재현한다. */
    @MockitoBean
    private KillSwitchPublisher killSwitchPublisher;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private record Login(String email, String accessToken, String sessionId) {
    }

    private Login signupAndLogin(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"name\":\"Tester\"}"))
                .andExpect(status().isCreated());
        return login(email);
    }

    private Login login(String email) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"deviceInfo\":\"test\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode tokens = objectMapper.readTree(body).path("data").path("tokens");
        return new Login(email, tokens.path("accessToken").asText(), tokens.path("sessionId").asText());
    }

    private String uniqueEmail() {
        return "cur-" + UUID.randomUUID() + "@doro.local";
    }

    private static String bearer(Login l) {
        return "Bearer " + l.accessToken();
    }

    private void expectExpired(Login l) throws Exception {
        mockMvc.perform(get(CURRENT).header("Authorization", bearer(l)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
    }

    @Test
    @DisplayName("활성 세션이면 204")
    void activeSessionReturns204() throws Exception {
        Login me = signupAndLogin(uniqueEmail());
        mockMvc.perform(get(CURRENT).header("Authorization", bearer(me))).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("인증 없는 요청은 401")
    void anonymousIs401() throws Exception {
        mockMvc.perform(get(CURRENT)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("로그아웃 후에도 IAM JWT 필터는 같은 토큰을 통과시키지만(Redis 없음) /current 는 401 SESSION_EXPIRED")
    void logoutIsVisibleThroughCurrent() throws Exception {
        Login me = signupAndLogin(uniqueEmail());
        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer(me))).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/sessions").header("Authorization", bearer(me))).andExpect(status().isOk());
        expectExpired(me);
    }

    @Test
    @DisplayName("revoke-others 로 종료된 세션은 401, 유지된 세션은 204")
    void revokeOthersIsVisible() throws Exception {
        String email = uniqueEmail();
        Login first = signupAndLogin(email);
        Login second = login(email);
        mockMvc.perform(post("/api/v1/sessions/revoke-others").header("Authorization", bearer(second))
                .param("currentSessionId", second.sessionId())).andExpect(status().isOk());

        expectExpired(first);
        mockMvc.perform(get(CURRENT).header("Authorization", bearer(second))).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("비밀번호 변경으로 종료된 다른 세션은 401")
    void passwordChangeIsVisible() throws Exception {
        String email = uniqueEmail();
        Login first = signupAndLogin(email);
        Login second = login(email);
        mockMvc.perform(put("/api/v1/users/me/password").header("Authorization", bearer(second))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"NewPassword456!\"}"))
                .andExpect(status().isOk());

        expectExpired(first);
        mockMvc.perform(get(CURRENT).header("Authorization", bearer(second))).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("역할 변경으로 종료된 세션은 401")
    void roleChangeIsVisible() throws Exception {
        Login me = signupAndLogin(uniqueEmail());
        when(guardClient.check(eq("system"), eq("doro"), eq("manage_roles"), anyString())).thenReturn(true);
        UUID userId = userRepository.findByEmailIgnoreCase(me.email()).orElseThrow().getId();

        userService.changeUserRole(userId, UserRole.ADMIN, UUID.randomUUID());

        expectExpired(me);
    }

    @Test
    @DisplayName("만료 시각이 지난 세션은 is_active 가 true 여도 401")
    void expiredSessionIs401() throws Exception {
        Login me = signupAndLogin(uniqueEmail());
        jdbcTemplate.update("UPDATE user_sessions SET expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), UUID.fromString(me.sessionId()));
        expectExpired(me);
    }

    @Test
    @DisplayName("DB 에 없는 세션의 토큰은 401 SESSION_EXPIRED")
    void unknownSessionIs401() throws Exception {
        Login me = signupAndLogin(uniqueEmail());
        jdbcTemplate.update("DELETE FROM refresh_tokens WHERE session_id = ?", UUID.fromString(me.sessionId()));
        jdbcTemplate.update("DELETE FROM user_sessions WHERE id = ?", UUID.fromString(me.sessionId()));
        expectExpired(me);
    }

    @Test
    @DisplayName("사용자가 ACTIVE 가 아니면 403 ACCOUNT_SUSPENDED")
    void suspendedUserIs403() throws Exception {
        Login me = signupAndLogin(uniqueEmail());
        var user = userRepository.findByEmailIgnoreCase(me.email()).orElseThrow();
        user.updateStatus(UserStatus.SUSPENDED);
        userRepository.save(user);

        mockMvc.perform(get(CURRENT).header("Authorization", bearer(me)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
    }

    @Test
    @DisplayName("sid 클레임이 없는 토큰은 401")
    void tokenWithoutSidIs401() throws Exception {
        Date now = new Date();
        String token = Jwts.builder()
                .header().keyId(keyProvider.getKeyId()).type("JWT").and()
                .issuer("https://auth.doro.local")
                .subject(UUID.randomUUID().toString())
                .claim("role", "USER")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 60_000))
                .signWith(keyProvider.getPrivateKey(), Jwts.SIG.RS256)
                .compact();

        mockMvc.perform(get(CURRENT).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("조회는 세션을 갱신(만료 연장/활동 시각 갱신)하지 않는다")
    void doesNotExtendSession() throws Exception {
        Login me = signupAndLogin(uniqueEmail());
        UUID sid = UUID.fromString(me.sessionId());
        UserSession before = sessionRepository.findById(sid).orElseThrow();
        Instant expiresBefore = before.getExpiresAt();
        Instant activeBefore = before.getLastActiveAt();

        mockMvc.perform(get(CURRENT).header("Authorization", bearer(me))).andExpect(status().isNoContent());

        UserSession after = sessionRepository.findById(sid).orElseThrow();
        assertThat(after.getExpiresAt()).isEqualTo(expiresBefore);
        assertThat(after.getLastActiveAt()).isEqualTo(activeBefore);
    }
}

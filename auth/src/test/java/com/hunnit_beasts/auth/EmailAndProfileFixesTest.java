package com.hunnit_beasts.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunnit_beasts.auth.domain.credential.entity.Credential;
import com.hunnit_beasts.auth.domain.credential.repository.CredentialRepository;
import com.hunnit_beasts.auth.domain.auth.dto.LoginRequest;
import com.hunnit_beasts.auth.domain.auth.dto.SignUpRequest;
import com.hunnit_beasts.auth.domain.auth.service.AuthService;
import com.hunnit_beasts.auth.domain.session.service.SessionService;
import com.hunnit_beasts.auth.domain.user.service.UserService;
import com.hunnit_beasts.auth.infrastructure.guard.GuardClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A5(프로필 부분 수정, 관리자 역할 변경 검증, 2FA 초기화), A6(이메일 정규화) 검증. */
@SpringBootTest(properties = "doro.iam.profile.image-max-length=100")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmailAndProfileFixesTest {

    private static final String PASSWORD = "Password123!";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private AuthService authService;
    @Autowired
    private UserService userService;
    @Autowired
    private SessionService sessionService;
    @Autowired
    private CredentialRepository credentialRepository;
    @MockitoBean
    private GuardClient guardClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private record Account(String email, String token, UUID userId) {
    }

    private int signup(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"name\":\"Tester\"}"))
                .andReturn().getResponse().getStatus();
    }

    private String loginToken(String email) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("tokens").path("accessToken").asText();
    }

    private Account account(String role) throws Exception {
        String email = "fix-" + UUID.randomUUID() + "@doro.local";
        assertThat(signup(email)).isEqualTo(201);
        jdbcTemplate.update("update users set role = ? where email = ?", role, email);
        UUID id = jdbcTemplate.queryForObject("select id from users where email = ?", UUID.class, email);
        return new Account(email, loginToken(email), id);
    }

    // ---------------- A6 ----------------

    @Test
    @DisplayName("A6: 가입 이메일은 소문자로 정규화되어 저장되고, 대소문자만 다른 이메일로 재가입하면 409")
    void signupNormalizesAndRejectsCaseVariants() throws Exception {
        String local = "Case-" + UUID.randomUUID();
        assertThat(signup(local + "@Doro.Local")).isEqualTo(201);

        assertThat(jdbcTemplate.queryForObject("select email from users where lower(email) = ?", String.class,
                (local + "@doro.local").toLowerCase())).isEqualTo((local + "@doro.local").toLowerCase());
        assertThat(signup(local.toLowerCase() + "@doro.local")).isEqualTo(409);
        assertThat(signup(local.toUpperCase() + "@DORO.LOCAL")).isEqualTo(409);
    }

    @Test
    @DisplayName("A6: 가입 시 쓴 대소문자와 달라도 로그인할 수 있다")
    void loginIsCaseInsensitive() throws Exception {
        String local = "Mixed-" + UUID.randomUUID();
        assertThat(signup(local + "@Doro.Local")).isEqualTo(201);

        loginToken(local.toLowerCase() + "@doro.local");
        loginToken(local.toUpperCase() + "@DORO.LOCAL");
        loginToken(local + "@Doro.Local");
    }

    // V9 의 CHECK(email = lower(email))가 있는 PostgreSQL 에서는 혼합 대소문자 행을 만들 수 없다(그 자체가 목적이다).
    // 제약이 없는 H2 단위 테스트에서만 과거 데이터에 대한 방어 코드를 검증한다.
    @Test
    @DisplayName("A6: 과거 데이터(혼합 대소문자로 저장된 행)도 어떤 대소문자로든 로그인되고 재가입은 막힌다")
    @DisabledIfEnvironmentVariable(named = "TEST_PG_URL", matches = ".+")
    void legacyMixedCaseRowStillLogsIn() throws Exception {
        String legacy = "Legacy.Mixed-" + UUID.randomUUID() + "@Doro.Local";
        String seed = "seed-" + UUID.randomUUID() + "@doro.local";
        assertThat(signup(seed)).isEqualTo(201);
        jdbcTemplate.update("update users set email = ? where email = ?", legacy, seed);

        loginToken(legacy);
        loginToken(legacy.toLowerCase());
        loginToken(legacy.toUpperCase());
        assertThat(signup(legacy.toLowerCase())).isEqualTo(409);
        mockMvc.perform(post("/api/v1/auth/lookup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + legacy.toLowerCase() + "\"}"))
                .andExpect(status().isOk());
    }

    // ---------------- A5(a) ----------------

    @Test
    @DisplayName("A5a: name 만 보내면 프로필 이미지는 유지되고, 빈 문자열이면 이미지가 제거된다")
    void profilePatchIsPartial() throws Exception {
        Account me = account("USER");
        String image = "data:image/png;base64,AAAA";

        mockMvc.perform(patch("/api/v1/users/me").header("Authorization", "Bearer " + me.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"profileImageUrl\":\"" + image + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.profileImageUrl").value(image));

        mockMvc.perform(patch("/api/v1/users/me").header("Authorization", "Bearer " + me.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"New Name\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("New Name"))
                .andExpect(jsonPath("$.data.profileImageUrl").value(image));

        mockMvc.perform(patch("/api/v1/users/me").header("Authorization", "Bearer " + me.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"profileImageUrl\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("New Name"))
                .andExpect(jsonPath("$.data.profileImageUrl").doesNotExist());
    }

    @Test
    @DisplayName("A5a: 프로필 이미지가 설정 최대 길이를 넘으면 400 INVALID_INPUT_VALUE")
    void profileImageTooLongIsRejected() throws Exception {
        Account me = account("USER");
        String tooLong = "x".repeat(101);

        mockMvc.perform(patch("/api/v1/users/me").header("Authorization", "Bearer " + me.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"profileImageUrl\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"));

        mockMvc.perform(patch("/api/v1/users/me").header("Authorization", "Bearer " + me.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"profileImageUrl\":\"" + ("data:image/png;base64," + "A".repeat(78)) + "\"}"))
                .andExpect(status().isOk());
    }

    // ---------------- A5(b) ----------------

    @Test
    @DisplayName("A5b: 역할 변경 요청의 role 이 없거나 비었거나 알 수 없으면 500 이 아니라 400 INVALID_INPUT")
    void roleChangeWithBadRoleIs400() throws Exception {
        Account admin = account("ADMIN");
        Account target = account("USER");
        when(guardClient.check(eq("system"), eq("doro"), eq("manage_roles"), anyString())).thenReturn(true);

        for (String body : new String[]{"{}", "{\"role\":\"\"}", "{\"role\":\"  \"}", "{\"role\":\"EMPEROR\"}"}) {
            mockMvc.perform(patch("/api/v1/admin/users/" + target.userId() + "/role")
                            .header("Authorization", "Bearer " + admin.token())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
    }

    @Test
    @DisplayName("A5b: 본인의 역할은 변경할 수 없고(400), 다른 사용자의 정상 변경은 200")
    void roleChangeOfSelfIsRejected() throws Exception {
        Account admin = account("ADMIN");
        Account target = account("USER");
        when(guardClient.check(eq("system"), eq("doro"), eq("manage_roles"), anyString())).thenReturn(true);

        mockMvc.perform(patch("/api/v1/admin/users/" + admin.userId() + "/role")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"SUPER_ADMIN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        mockMvc.perform(patch("/api/v1/admin/users/" + target.userId() + "/role")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("ADMIN"));
    }

    @Test
    @DisplayName("A5b: 권한(Guard)이 없으면 입력이 잘못되었어도 대상 존재 여부와 무관하게 403 이 먼저 적용된다")
    void guardCheckStillComesFirst() throws Exception {
        Account admin = account("ADMIN");
        when(guardClient.check(eq("system"), eq("doro"), eq("manage_roles"), anyString())).thenReturn(false);

        mockMvc.perform(patch("/api/v1/admin/users/" + UUID.randomUUID() + "/role")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/admin/users/" + admin.userId() + "/role")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
    }

    // ---------------- A5(c) ----------------

    @Test
    @DisplayName("A5c: 2FA 초기화는 대기 중 시크릿도 지우고 대상의 세션을 모두 종료한다")
    void resetTwoFactorClearsPendingSecretAndRevokesSessions() throws Exception {
        Account target = account("USER");
        UUID admin = UUID.randomUUID();
        Credential credential = credentialRepository.findByUserId(target.userId()).orElseThrow();
        credential.updateTotpSecret("JBSWY3DPEHPK3PXP");
        credential.beginTotpEnrollment("KRSXG5CTMVRXEZLU");
        credentialRepository.save(credential);
        assertThat(sessionService.getActiveSessions(target.userId())).isNotEmpty();
        when(guardClient.check(eq("user"), eq(target.userId().toString()), eq("can_reset_2fa"), anyString())).thenReturn(true);

        userService.resetUserTwoFactor(target.userId(), admin);

        Credential after = credentialRepository.findByUserId(target.userId()).orElseThrow();
        assertThat(after.getTotpSecret()).isNull();
        assertThat(after.getPendingTotpSecret()).isNull();
        assertThat(sessionService.getActiveSessions(target.userId())).isEmpty();
    }
}

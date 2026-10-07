package com.hunnit_beasts.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.core.totp.TotpService;
import com.hunnit_beasts.auth.domain.session.service.SessionService;
import com.hunnit_beasts.auth.domain.auth.service.AuthService;
import com.hunnit_beasts.auth.domain.credential.entity.Credential;
import com.hunnit_beasts.auth.domain.credential.repository.CredentialRepository;
import com.hunnit_beasts.auth.domain.user.entity.UserStatus;
import com.hunnit_beasts.auth.domain.user.repository.UserRepository;
import com.hunnit_beasts.auth.domain.user.service.AccountPurgeService;
import com.hunnit_beasts.auth.domain.user.service.UserRelationSyncService;
import com.hunnit_beasts.auth.infrastructure.guard.GuardClient;
import com.hunnit_beasts.auth.infrastructure.guard.GuardClient.TupleDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 회원탈퇴: 재확인 → 유예(로그인 시 복구) → 영구 익명화 → 재가입까지 정책 전체를 검증한다. */
@SpringBootTest(properties = "doro.iam.account-deletion.grace-days=30")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountDeletionTest {

    private static final String PASSWORD = "Password123!";
    private static final long GRACE_DAYS = 30;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CredentialRepository credentialRepository;
    @Autowired
    private AuthService authService;
    @Autowired
    private TotpService totpService;
    @Autowired
    private SessionService sessionService;
    @Autowired
    private AccountPurgeService purgeService;
    @Autowired
    private UserRelationSyncService relationSyncService;
    @Autowired
    private TransactionTemplate transaction;
    @MockitoBean
    private GuardClient guardClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private record Account(String email, UUID id, String token) {
    }

    @BeforeEach
    void resetGuardMock() {
        reset(guardClient);
    }

    private Account account() throws Exception {
        String email = "del-" + UUID.randomUUID() + "@doro.local";
        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"name\":\"Leaver\"}"))
                .andExpect(status().isCreated());
        UUID id = jdbc.queryForObject("select id from users where email = ?", UUID.class, email);
        return new Account(email, id, login(email));
    }

    private String login(String email) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("tokens").path("accessToken").asText();
    }

    private int requestDeletion(Account account, String password, String totp) throws Exception {
        String body = totp == null
                ? "{\"password\":\"" + password + "\"}"
                : "{\"password\":\"" + password + "\",\"totpCode\":\"" + totp + "\"}";
        return mockMvc.perform(post("/api/v1/users/me/deletion")
                        .header("Authorization", "Bearer " + account.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getStatus();
    }

    private <T> T inTx(java.util.function.Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("select status from users where id = ?", String.class, id);
    }

    private void backdateDeletionRequest(UUID id, Duration age) {
        jdbc.update("update users set deletion_requested_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minus(age)), id);
    }

    private String totpCode(String secret, long stepOffset) {
        byte[] key = ReflectionTestUtils.invokeMethod(totpService, "decodeBase32", secret);
        int value = ReflectionTestUtils.invokeMethod(totpService, "generateCodeForStep", key,
                Instant.now().getEpochSecond() / 30 + stepOffset);
        return String.format("%06d", value);
    }

    // ---------------- 요청 ----------------

    @Test
    @DisplayName("로그인 없이는 탈퇴를 요청할 수 없다")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/users/me/deletion").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("비밀번호가 틀리면 탈퇴되지 않고 실패가 잠금 카운트에 합산된다")
    void wrongPasswordDoesNotDelete() throws Exception {
        Account account = account();

        assertThat(requestDeletion(account, "Wrong-Password1!", null)).isEqualTo(401);

        assertThat(statusOf(account.id())).isEqualTo("ACTIVE");
        assertThat(credentialRepository.findByUserId(account.id()).orElseThrow().getFailedAttempts()).isEqualTo(1);
        // 세션도 그대로여야 한다
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + account.token()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("비밀번호 실패가 누적되면 탈퇴 API 도 잠겨, 토큰만 탈취해서는 비밀번호를 대입할 수 없다")
    void repeatedWrongPasswordsLockTheAccount() throws Exception {
        Account account = account();

        for (int i = 0; i < Credential.MAX_FAILED_ATTEMPTS; i++) {
            assertThat(requestDeletion(account, "Wrong-Password1!", null)).isEqualTo(401);
        }
        // 이제는 올바른 비밀번호여도 잠겨 있다
        assertThat(requestDeletion(account, PASSWORD, null)).isEqualTo(403);
        assertThat(statusOf(account.id())).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("올바른 비밀번호면 즉시 탈퇴 대기 상태가 되고 모든 세션·기존 토큰이 무효화되며 30일 뒤 처리 시각을 알려 준다")
    void validRequestSchedulesDeletionAndRevokesEverything() throws Exception {
        Account account = account();
        String secondDeviceToken = login(account.email());

        String body = mockMvc.perform(post("/api/v1/users/me/deletion")
                        .header("Authorization", "Bearer " + account.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Instant purgeAt = Instant.parse(objectMapper.readTree(body).path("data").path("scheduledPurgeAt").asText());
        assertThat(purgeAt).isBetween(
                Instant.now().plus(Duration.ofDays(GRACE_DAYS)).minusSeconds(60),
                Instant.now().plus(Duration.ofDays(GRACE_DAYS)).plusSeconds(60));
        assertThat(statusOf(account.id())).isEqualTo("PENDING_DELETION");
        assertThat(jdbc.queryForObject("select count(*) from user_sessions where user_id = ? and is_active = true",
                Integer.class, account.id())).isZero();
        // 서브 서비스(SDK)가 쓰는 토큰 확인은 DB 가 기준이다: 요청에 쓴 세션과 다른 기기의 세션 모두 거부된다.
        // (IAM 자체 필터의 즉시 차단은 Redis 블랙리스트에 의존하므로, Redis 가 없는 CI 에서도 같은 결과가 나오게 DB 로 검증한다)
        List<UUID> sessionIds = jdbc.queryForList("select id from user_sessions where user_id = ?", UUID.class, account.id());
        assertThat(sessionIds).isNotEmpty();
        for (UUID sessionId : sessionIds) {
            assertThatThrownBy(() -> sessionService.assertSessionLive(sessionId)).isInstanceOf(AuthException.class);
        }
        assertThat(secondDeviceToken).isNotBlank();
    }

    @Test
    @DisplayName("2FA 사용자는 OTP 코드가 없거나 틀리면 탈퇴할 수 없고, 올바른 코드면 탈퇴된다")
    void twoFactorUsersMustProvideTheCode() throws Exception {
        Account account = account();
        String secret = authService.setupTotp(account.id()).secret();
        authService.verifyTotp(account.id(), totpCode(secret, 0));

        assertThat(requestDeletion(account, PASSWORD, null)).isEqualTo(401);
        assertThat(requestDeletion(account, PASSWORD, "000000")).isEqualTo(401);
        assertThat(statusOf(account.id())).isEqualTo("ACTIVE");

        // 가입 확인에 쓴 코드는 재사용이 막혀 있으므로 다음 구간의 코드를 쓴다
        jdbc.update("update credentials set failed_attempts = 0, locked_until = null where user_id = ?", account.id());
        assertThat(requestDeletion(account, PASSWORD, totpCode(secret, 1))).isEqualTo(200);
        assertThat(statusOf(account.id())).isEqualTo("PENDING_DELETION");
    }

    @Test
    @DisplayName("관리자는 역할을 내리기 전에는 탈퇴할 수 없다")
    void adminsCannotDeleteThemselves() throws Exception {
        Account account = account();
        jdbc.update("update users set role = 'ADMIN' where id = ?", account.id());
        String adminToken = login(account.email());

        int code = mockMvc.perform(post("/api/v1/users/me/deletion")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + PASSWORD + "\"}"))
                .andReturn().getResponse().getStatus();

        assertThat(code).isEqualTo(400);
        assertThat(statusOf(account.id())).isEqualTo("ACTIVE");
    }

    // ---------------- 유예 ----------------

    @Test
    @DisplayName("유예 중 계정 조회는 막히지 않고, 틀린 비밀번호로는 복구되지 않는다")
    void gracePeriodAccountCanBeLookedUpButNotRestoredWithoutPassword() throws Exception {
        Account account = account();
        assertThat(requestDeletion(account, PASSWORD, null)).isEqualTo(200);

        mockMvc.perform(post("/api/v1/auth/lookup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + account.email() + "\"}"))
                .andExpect(status().isOk());
        // 상태는 비밀번호 검증 전에 드러나지 않는다: 틀린 비밀번호는 일반 로그인 실패와 같은 응답이다
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + account.email() + "\",\"password\":\"Wrong-Password1!\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        assertThat(statusOf(account.id())).isEqualTo("PENDING_DELETION");
    }

    @Test
    @DisplayName("유예 중 올바른 비밀번호로 로그인하면 탈퇴가 취소되고 Guard 튜플이 다시 보장된다")
    void loginDuringGraceRestoresTheAccount() throws Exception {
        Account account = account();
        assertThat(requestDeletion(account, PASSWORD, null)).isEqualTo(200);
        clearInvocations(guardClient);

        String newToken = login(account.email());

        assertThat(statusOf(account.id())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select deletion_requested_at from users where id = ?",
                java.sql.Timestamp.class, account.id())).isNull();
        verify(guardClient, atLeastOnce()).writeTuples(anyList());
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + newToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("복구와 영구 처리가 경쟁해도 먼저 실행된 한쪽만 적용된다 (조건부 UPDATE)")
    void restoreAndPurgeAreMutuallyExclusive() throws Exception {
        Account account = account();
        assertThat(requestDeletion(account, PASSWORD, null)).isEqualTo(200);
        backdateDeletionRequest(account.id(), Duration.ofDays(GRACE_DAYS + 1));
        Instant now = Instant.now();
        Instant cutoff = now.minus(Duration.ofDays(GRACE_DAYS));

        // 복구가 먼저 이기면 영구 처리는 아무것도 바꾸지 못한다
        assertThat(inTx(() -> userRepository.restoreIfPendingDeletion(
                account.id(), UserStatus.ACTIVE, UserStatus.PENDING_DELETION, now))).isEqualTo(1);
        assertThat(inTx(() -> userRepository.anonymizeIfDue(account.id(), "x@deleted.invalid", "x",
                UserStatus.DELETED, UserStatus.PENDING_DELETION, cutoff, now))).isZero();
        assertThat(jdbc.queryForObject("select email from users where id = ?", String.class, account.id()))
                .isEqualTo(account.email());

        // 영구 처리가 먼저 이기면 복구는 아무것도 되돌리지 못한다
        jdbc.update("update users set status = 'PENDING_DELETION', deletion_requested_at = ? where id = ?",
                java.sql.Timestamp.from(now.minus(Duration.ofDays(GRACE_DAYS + 1))), account.id());
        assertThat(inTx(() -> userRepository.anonymizeIfDue(account.id(), "y@deleted.invalid", "y",
                UserStatus.DELETED, UserStatus.PENDING_DELETION, cutoff, now))).isEqualTo(1);
        assertThat(inTx(() -> userRepository.restoreIfPendingDeletion(
                account.id(), UserStatus.ACTIVE, UserStatus.PENDING_DELETION, now))).isZero();
        assertThat(statusOf(account.id())).isEqualTo("DELETED");
    }

    // ---------------- 영구 처리 ----------------

    @Test
    @DisplayName("유예 기간 안에는 영구 처리되지 않는다")
    void notPurgedBeforeTheGracePeriodEnds() throws Exception {
        Account account = account();
        assertThat(requestDeletion(account, PASSWORD, null)).isEqualTo(200);
        backdateDeletionRequest(account.id(), Duration.ofDays(GRACE_DAYS - 1));

        purgeService.purgeDueAccounts(Instant.now());

        assertThat(statusOf(account.id())).isEqualTo("PENDING_DELETION");
        // 다른 테스트가 남긴 대기 계정을 처리하는 호출은 있을 수 있으므로, 이 계정의 튜플만 지워지지 않았는지 본다.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TupleDto>> tuples = ArgumentCaptor.forClass(List.class);
        verify(guardClient, atLeast(0)).deleteTuplesOrThrow(tuples.capture());
        assertThat(tuples.getAllValues().toString()).doesNotContain(account.id().toString());
    }

    @Test
    @DisplayName("유예가 끝나면 개인정보가 익명화되고 자격증명·세션이 삭제되며 Guard 튜플이 지워진다")
    void purgeAnonymizesAndRemovesPersonalData() throws Exception {
        Account account = account();
        jdbc.update("update users set profile_image_url = 'https://img.example/a.png' where id = ?", account.id());
        assertThat(requestDeletion(account, PASSWORD, null)).isEqualTo(200);
        backdateDeletionRequest(account.id(), Duration.ofDays(GRACE_DAYS + 1));

        assertThat(purgeService.purgeDueAccounts(Instant.now())).isEqualTo(1);

        var row = jdbc.queryForMap("select email, name, profile_image_url, status from users where id = ?", account.id());
        assertThat(row.get("status")).isEqualTo("DELETED");
        assertThat(row.get("name")).isEqualTo("탈퇴한 사용자");
        assertThat((String) row.get("email")).startsWith("deleted-").endsWith("@deleted.invalid")
                .doesNotContain(account.email());
        assertThat(row.get("profile_image_url")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from credentials where user_id = ?", Integer.class, account.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from user_sessions where user_id = ?", Integer.class, account.id())).isZero();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TupleDto>> tuples = ArgumentCaptor.forClass(List.class);
        verify(guardClient).deleteTuplesOrThrow(tuples.capture());
        assertThat(tuples.getValue().toString()).contains(account.id().toString());

        // 원래 이메일로는 더 이상 로그인할 수 없다
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + account.email() + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("영구 처리 뒤에는 같은 이메일로 다시 가입할 수 있고 새 계정은 새 ID 를 받는다")
    void emailCanBeReusedAfterPurge() throws Exception {
        Account account = account();
        assertThat(requestDeletion(account, PASSWORD, null)).isEqualTo(200);

        // 유예 중에는 이메일이 아직 점유되어 있다
        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + account.email() + "\",\"password\":\"" + PASSWORD + "\",\"name\":\"New\"}"))
                .andExpect(status().isConflict());

        backdateDeletionRequest(account.id(), Duration.ofDays(GRACE_DAYS + 1));
        purgeService.purgeDueAccounts(Instant.now());

        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + account.email() + "\",\"password\":\"" + PASSWORD + "\",\"name\":\"New\"}"))
                .andExpect(status().isCreated());
        UUID newId = jdbc.queryForObject("select id from users where email = ?", UUID.class, account.email());
        assertThat(newId).isNotEqualTo(account.id());
        assertThat(statusOf(newId)).isEqualTo("ACTIVE");
        assertThat(statusOf(account.id())).isEqualTo("DELETED");
    }

    @Test
    @DisplayName("Guard 장애로 튜플을 못 지우면 상태를 바꾸지 않고, 복구된 뒤 다음 주기에 처리된다")
    void guardFailureKeepsTheAccountPendingAndIsRetried() throws Exception {
        Account account = account();
        assertThat(requestDeletion(account, PASSWORD, null)).isEqualTo(200);
        backdateDeletionRequest(account.id(), Duration.ofDays(GRACE_DAYS + 1));

        doThrow(new IllegalStateException("guard down")).when(guardClient).deleteTuplesOrThrow(anyList());
        assertThat(purgeService.purgeDueAccounts(Instant.now())).isZero();
        assertThat(statusOf(account.id())).isEqualTo("PENDING_DELETION");
        assertThat(jdbc.queryForObject("select count(*) from credentials where user_id = ?", Integer.class, account.id())).isOne();

        reset(guardClient);
        assertThat(purgeService.purgeDueAccounts(Instant.now())).isEqualTo(1);
        assertThat(statusOf(account.id())).isEqualTo("DELETED");
    }

    @Test
    @DisplayName("기동 시 튜플 동기화는 영구 탈퇴한 계정의 튜플을 되살리지 않는다")
    void startupSyncSkipsDeletedAccounts() throws Exception {
        Account account = account();
        assertThat(requestDeletion(account, PASSWORD, null)).isEqualTo(200);
        backdateDeletionRequest(account.id(), Duration.ofDays(GRACE_DAYS + 1));
        purgeService.purgeDueAccounts(Instant.now());
        reset(guardClient);

        relationSyncService.syncAllUsersOnStartup();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TupleDto>> written = ArgumentCaptor.forClass(List.class);
        verify(guardClient, atLeastOnce()).writeTuples(written.capture());
        assertThat(written.getAllValues().toString()).doesNotContain(account.id().toString());
    }

    // ---------------- 서브 서비스용 탈퇴 ID 목록 ----------------

    private JsonNode feed(String query) throws Exception {
        String body = mockMvc.perform(get("/internal/v1/deleted-users" + query))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data");
    }

    private boolean feedContains(JsonNode feed, UUID id) {
        for (JsonNode item : feed.path("items")) {
            if (id.toString().equals(item.path("userId").asText())) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("탈퇴 ID 목록에는 영구 처리된 계정만 ID 와 시각으로 나오고, 유예 중 계정과 개인정보는 나오지 않는다")
    void deletedUsersFeedListsOnlyPurgedAccountsWithoutPii() throws Exception {
        Account pending = account();
        Account purged = account();
        assertThat(requestDeletion(pending, PASSWORD, null)).isEqualTo(200);
        assertThat(requestDeletion(purged, PASSWORD, null)).isEqualTo(200);
        backdateDeletionRequest(purged.id(), Duration.ofDays(GRACE_DAYS + 1));
        Instant before = Instant.now().minusSeconds(5);
        purgeService.purgeDueAccounts(Instant.now());

        JsonNode feed = feed("?since=" + before);

        assertThat(feedContains(feed, purged.id())).isTrue();
        assertThat(feedContains(feed, pending.id())).isFalse();
        assertThat(feed.toString()).doesNotContain(purged.email()).doesNotContain("Leaver");
        assertThat(feed.path("nextSince").asText()).isNotBlank();
    }

    @Test
    @DisplayName("since 이후에 처리된 항목만 나오고, limit 은 1~500 으로 제한되며, 잘못된 since 는 400")
    void deletedUsersFeedIsIncrementalAndBounded() throws Exception {
        Account account = account();
        assertThat(requestDeletion(account, PASSWORD, null)).isEqualTo(200);
        backdateDeletionRequest(account.id(), Duration.ofDays(GRACE_DAYS + 1));
        purgeService.purgeDueAccounts(Instant.now());

        assertThat(feedContains(feed("?since=" + Instant.now().minusSeconds(60)), account.id())).isTrue();
        assertThat(feedContains(feed("?since=" + Instant.now().plusSeconds(60)), account.id())).isFalse();
        assertThat(feed("?limit=0&since=" + Instant.now().minusSeconds(60)).path("items").size()).isLessThanOrEqualTo(1);
        assertThat(feed("?limit=100000").path("items").size()).isLessThanOrEqualTo(500);
        mockMvc.perform(get("/internal/v1/deleted-users?since=not-a-time")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("게이트웨이를 거친 요청(X-Forwarded-For, X-Real-IP)에는 이 내부 경로가 없는 것처럼 404 로 응답한다")
    void deletedUsersFeedIsHiddenFromProxiedRequests() throws Exception {
        mockMvc.perform(get("/internal/v1/deleted-users").header("X-Forwarded-For", "203.0.113.9"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/internal/v1/deleted-users").header("X-Real-IP", "203.0.113.9"))
                .andExpect(status().isNotFound());
    }
}

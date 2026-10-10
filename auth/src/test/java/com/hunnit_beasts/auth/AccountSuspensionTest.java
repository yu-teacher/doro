package com.hunnit_beasts.auth;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.domain.auth.dto.LoginRequest;
import com.hunnit_beasts.auth.domain.auth.dto.RefreshTokenRequest;
import com.hunnit_beasts.auth.domain.auth.dto.SignUpRequest;
import com.hunnit_beasts.auth.domain.auth.dto.TokenResponse;
import com.hunnit_beasts.auth.domain.auth.service.AuthService;
import com.hunnit_beasts.auth.domain.credential.entity.Credential;
import com.hunnit_beasts.auth.domain.credential.repository.CredentialRepository;
import com.hunnit_beasts.auth.domain.credential.service.CredentialService;
import com.hunnit_beasts.auth.domain.session.service.SessionService;
import com.hunnit_beasts.auth.domain.user.dto.UserProfileResponse;
import com.hunnit_beasts.auth.domain.user.service.AccountSuspensionService;
import com.hunnit_beasts.auth.domain.user.service.UserService;
import com.hunnit_beasts.auth.infrastructure.guard.GuardClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** 관리자의 계정 정지·해제·잠금 해제: 효과(로그인·갱신 차단, 세션 종료), 인가 규칙, 입력 검증. */
@SpringBootTest
@ActiveProfiles("test")
class AccountSuspensionTest {

    private static final String PASSWORD = "Password123!";

    @Autowired private AuthService authService;
    @Autowired private AccountSuspensionService suspension;
    @Autowired private UserService userService;
    @Autowired private SessionService sessionService;
    @Autowired private CredentialService credentialService;
    @Autowired private CredentialRepository credentialRepository;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private GuardClient guardClient;

    private final UUID admin = UUID.randomUUID();

    @BeforeEach
    void adminIsAdminButNotSuperAdmin() {
        when(guardClient.check(eq("system"), eq("doro"), eq("admin"), anyString())).thenReturn(true);
        when(guardClient.check(eq("system"), eq("doro"), eq("manage_roles"), anyString())).thenReturn(false);
    }

    private String email;

    private UUID newUser() {
        email = "susp-" + UUID.randomUUID() + "@doro.local";
        return authService.signup(new SignUpRequest(email, PASSWORD, "Susp Target"));
    }

    private TokenResponse login() {
        return authService.login(new LoginRequest(email, PASSWORD, "test"), "127.0.0.1", "UA-" + UUID.randomUUID()).tokens();
    }

    private void setRole(UUID id, String role) {
        jdbc.update("update users set role = ? where id = ?", role, id);
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("select status from users where id = ?", String.class, id);
    }

    private static void assertCode(Throwable e, ErrorCode code) {
        assertThat(e).isInstanceOfSatisfying(AuthException.class, ae -> assertThat(ae.getErrorCode()).isEqualTo(code));
    }

    @Test
    @DisplayName("정지하면 기존 세션이 모두 끝나고, 로그인과 토큰 갱신이 막히며, 사유·시각·정지한 관리자가 남는다")
    void suspendBlocksEverything() {
        UUID target = newUser();
        TokenResponse tokens = login();
        assertThat(sessionService.getActiveSessions(target)).hasSize(1);

        UserProfileResponse result = suspension.suspend(target, "  스팸 게시  ", admin);

        assertThat(result.status()).isEqualTo("SUSPENDED");
        assertThat(result.suspensionReason()).isEqualTo("스팸 게시");
        assertThat(result.suspendedAt()).isNotNull();
        assertThat(jdbc.queryForObject("select suspended_by from users where id = ?", UUID.class, target)).isEqualTo(admin);
        assertThat(sessionService.getActiveSessions(target)).isEmpty();
        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(tokens.refreshToken())))
                .satisfies(e -> assertThat(e).isInstanceOf(AuthException.class));
        assertThatThrownBy(this::login).satisfies(e -> assertCode(e, ErrorCode.ACCOUNT_SUSPENDED));
    }

    @Test
    @DisplayName("해제하면 다시 로그인할 수 있고 정지 기록은 지워진다")
    void reinstateRestoresLogin() {
        UUID target = newUser();
        suspension.suspend(target, "테스트", admin);

        UserProfileResponse result = suspension.reinstate(target, admin);

        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.suspendedAt()).isNull();
        assertThat(result.suspensionReason()).isNull();
        assertThat(jdbc.queryForObject("select suspended_by from users where id = ?", UUID.class, target)).isNull();
        assertThatCode(this::login).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("이미 정지된 계정을 다시 정지해도 오류 없이 처음 기록을 유지한다(멱등)")
    void suspendIsIdempotent() {
        UUID target = newUser();
        UserProfileResponse first = suspension.suspend(target, "처음 사유", admin);
        UserProfileResponse second = suspension.suspend(target, "다른 사유", admin);

        assertThat(second.suspensionReason()).isEqualTo("처음 사유");
        assertThat(second.suspendedAt()).isEqualTo(first.suspendedAt());
    }

    @Test
    @DisplayName("관리자가 아니면(Guard 판정) 거부하고, 대상이 있는지 없는지도 알려 주지 않는다")
    void nonAdminIsDeniedBeforeTargetLookup() {
        when(guardClient.check(eq("system"), eq("doro"), eq("admin"), anyString())).thenReturn(false);
        UUID existing = newUser();
        UUID missing = UUID.randomUUID();

        assertThatThrownBy(() -> suspension.suspend(existing, "x", admin)).satisfies(e -> assertCode(e, ErrorCode.ACCESS_DENIED));
        assertThatThrownBy(() -> suspension.suspend(missing, "x", admin)).satisfies(e -> assertCode(e, ErrorCode.ACCESS_DENIED));
        assertThatThrownBy(() -> suspension.reinstate(missing, admin)).satisfies(e -> assertCode(e, ErrorCode.ACCESS_DENIED));
        assertThatThrownBy(() -> suspension.unlock(missing, admin)).satisfies(e -> assertCode(e, ErrorCode.ACCESS_DENIED));
        assertThat(statusOf(existing)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("본인과 최고 관리자는 정지할 수 없고, 관리자 계정은 최고 관리자만 정지·해제할 수 있다")
    void roleRules() {
        assertThatThrownBy(() -> suspension.suspend(admin, "x", admin)).satisfies(e -> assertCode(e, ErrorCode.INVALID_INPUT));

        UUID superAdmin = newUser();
        setRole(superAdmin, "SUPER_ADMIN");
        when(guardClient.check(eq("system"), eq("doro"), eq("manage_roles"), anyString())).thenReturn(true);
        assertThatThrownBy(() -> suspension.suspend(superAdmin, "x", admin))
                .as("최고 관리자 권한이 있어도 최고 관리자는 정지할 수 없다").satisfies(e -> assertCode(e, ErrorCode.ACCESS_DENIED));
        assertThat(statusOf(superAdmin)).isEqualTo("ACTIVE");

        UUID otherAdmin = newUser();
        setRole(otherAdmin, "ADMIN");
        when(guardClient.check(eq("system"), eq("doro"), eq("manage_roles"), anyString())).thenReturn(false);
        assertThatThrownBy(() -> suspension.suspend(otherAdmin, "x", admin)).satisfies(e -> assertCode(e, ErrorCode.ACCESS_DENIED));
        assertThat(statusOf(otherAdmin)).isEqualTo("ACTIVE");

        when(guardClient.check(eq("system"), eq("doro"), eq("manage_roles"), anyString())).thenReturn(true);
        assertThat(suspension.suspend(otherAdmin, "x", admin).status()).isEqualTo("SUSPENDED");
        when(guardClient.check(eq("system"), eq("doro"), eq("manage_roles"), anyString())).thenReturn(false);
        assertThatThrownBy(() -> suspension.reinstate(otherAdmin, admin)).satisfies(e -> assertCode(e, ErrorCode.ACCESS_DENIED));
    }

    @Test
    @DisplayName("사유는 필수이고 200자 이내여야 한다")
    void reasonIsValidated() {
        UUID target = newUser();
        for (String bad : new String[]{null, "", "   ", "x".repeat(AccountSuspensionService.MAX_REASON_LENGTH + 1)}) {
            assertThatThrownBy(() -> suspension.suspend(target, bad, admin)).satisfies(e -> assertCode(e, ErrorCode.INVALID_INPUT));
        }
        assertThat(statusOf(target)).isEqualTo("ACTIVE");
        assertThat(suspension.suspend(target, "x".repeat(AccountSuspensionService.MAX_REASON_LENGTH), admin).status()).isEqualTo("SUSPENDED");
    }

    @Test
    @DisplayName("탈퇴 유예 중인 계정은 정지할 수 없고, 정지 중이 아닌 계정의 해제는 409 다")
    void stateConflicts() {
        UUID pending = newUser();
        jdbc.update("update users set status = 'PENDING_DELETION' where id = ?", pending);
        assertThatThrownBy(() -> suspension.suspend(pending, "x", admin)).satisfies(e -> assertCode(e, ErrorCode.ACCOUNT_STATE_CONFLICT));
        assertThatThrownBy(() -> suspension.reinstate(pending, admin)).satisfies(e -> assertCode(e, ErrorCode.ACCOUNT_STATE_CONFLICT));

        UUID active = newUser();
        assertThat(suspension.reinstate(active, admin).status()).as("활성 계정의 해제는 변화 없이 성공").isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("비밀번호 실패로 잠긴 계정은 잠금 해제로 바로 로그인할 수 있다")
    void unlockClearsPasswordLock() {
        UUID target = newUser();
        for (int i = 0; i < Credential.MAX_FAILED_ATTEMPTS; i++) {
            credentialService.recordFailedAttempt(target);
        }
        assertThatThrownBy(this::login).satisfies(e -> assertCode(e, ErrorCode.ACCOUNT_LOCKED));
        assertThat(userService.getAllUsers(admin).stream().filter(u -> u.id().equals(target)).findFirst().orElseThrow().locked()).isTrue();

        UserProfileResponse result = suspension.unlock(target, admin);

        assertThat(result.locked()).isFalse();
        assertThat(credentialRepository.findByUserId(target).orElseThrow().getFailedAttempts()).isZero();
        assertThatCode(this::login).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("관리자 목록에 정지 정보와 잠금 상태가 담긴다")
    void adminListShowsModerationState() {
        UUID target = newUser();
        suspension.suspend(target, "목록 확인", admin);

        UserProfileResponse row = userService.getAllUsers(admin).stream().filter(u -> u.id().equals(target)).findFirst().orElseThrow();
        assertThat(row.status()).isEqualTo("SUSPENDED");
        assertThat(row.suspensionReason()).isEqualTo("목록 확인");
        assertThat(row.locked()).isFalse();
    }
}

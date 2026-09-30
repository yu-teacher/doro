package com.hunnit_beasts.auth;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.domain.auth.dto.LoginRequest;
import com.hunnit_beasts.auth.domain.auth.dto.SignUpRequest;
import com.hunnit_beasts.auth.domain.auth.service.AuthService;
import com.hunnit_beasts.auth.domain.session.service.SessionService;
import com.hunnit_beasts.auth.domain.user.entity.UserRole;
import com.hunnit_beasts.auth.domain.user.service.UserService;
import com.hunnit_beasts.auth.infrastructure.guard.GuardClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class AdminHardeningTest {

    @Autowired
    private AuthService authService;
    @Autowired
    private UserService userService;
    @Autowired
    private SessionService sessionService;
    @MockitoBean
    private GuardClient guardClient;

    private UUID newUserWithSession() {
        String email = "role-" + UUID.randomUUID() + "@doro.local";
        UUID userId = authService.signup(new SignUpRequest(email, "Password123!", "Role Target"));
        authService.login(new LoginRequest(email, "Password123!", "test"), "127.0.0.1", "UA-" + UUID.randomUUID());
        return userId;
    }

    @Test
    @DisplayName("역할을 변경하면 대상 사용자의 기존 세션이 모두 종료된다 (낡은 역할 클레임 제거)")
    void roleChangeRevokesTargetSessions() {
        UUID admin = UUID.randomUUID();
        UUID target = newUserWithSession();
        when(guardClient.check(eq("system"), eq("doro"), eq("manage_roles"), anyString())).thenReturn(true);
        assertThat(sessionService.getActiveSessions(target)).hasSize(1);

        userService.changeUserRole(target, UserRole.ADMIN, admin);

        assertThat(sessionService.getActiveSessions(target)).isEmpty();
    }

    @Test
    @DisplayName("관리자 목록 조회는 JWT 역할 클레임이 아니라 Guard 판정으로 허용된다")
    void adminListingIsDelegatedToGuard() {
        UUID admin = UUID.randomUUID();
        when(guardClient.check(eq("system"), eq("doro"), eq("admin"), anyString())).thenReturn(false);
        assertThatThrownBy(() -> userService.getAllUsers(admin))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));

        when(guardClient.check(eq("system"), eq("doro"), eq("admin"), anyString())).thenReturn(true);
        assertThat(userService.getAllUsers(admin)).isNotNull();
    }
}

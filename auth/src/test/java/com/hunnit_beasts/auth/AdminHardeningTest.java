package com.hunnit_beasts.auth;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.domain.auth.dto.LoginRequest;
import com.hunnit_beasts.auth.domain.auth.dto.SignUpRequest;
import com.hunnit_beasts.auth.domain.auth.service.AuthService;
import com.hunnit_beasts.auth.domain.session.service.SessionService;
import com.hunnit_beasts.auth.domain.user.dto.UserProfileResponse;
import com.hunnit_beasts.auth.domain.user.entity.UserRole;
import com.hunnit_beasts.auth.domain.user.service.UserService;
import com.hunnit_beasts.auth.infrastructure.guard.GuardClient;
import com.hunnit_beasts.auth.interfaces.api.AdminController;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.test.context.support.WithMockUser;
import com.hunnit_beasts.auth.common.response.ApiResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    @Autowired
    private AdminController adminController;
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

    @Test
    @DisplayName("페이지를 나눈 목록은 페이지 사이에 겹치지 않고, 전체 수를 알려 준다")
    void pagedListingDoesNotOverlap() {
        UUID admin = UUID.randomUUID();
        when(guardClient.check(eq("system"), eq("doro"), eq("admin"), anyString())).thenReturn(true);
        for (int i = 0; i < 5; i++) newUserWithSession();

        Page<UserProfileResponse> first = userService.getUsers(admin, PageRequest.of(0, 2));
        Page<UserProfileResponse> second = userService.getUsers(admin, PageRequest.of(1, 2));

        assertThat(first.getContent()).hasSize(2);
        assertThat(second.getContent()).hasSize(2);
        Set<UUID> ids = new HashSet<>();
        first.getContent().forEach(u -> ids.add(u.id()));
        second.getContent().forEach(u -> ids.add(u.id()));
        assertThat(ids).hasSize(4);
        assertThat(first.getTotalElements()).isGreaterThanOrEqualTo(5);
        assertThat(userService.getAllUsers(admin)).hasSize((int) first.getTotalElements());
    }

    @Test
    @DisplayName("페이지 목록도 Guard 가 거부하면 조회할 수 없다")
    void pagedListingIsAlsoGuarded() {
        when(guardClient.check(eq("system"), eq("doro"), eq("admin"), anyString())).thenReturn(false);
        assertThatThrownBy(() -> userService.getUsers(UUID.randomUUID(), PageRequest.of(0, 10)))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("컨트롤러: size 가 범위를 벗어나면 거부하고, 페이지 요청에는 X-Total-Count 를 붙이며, 파라미터가 없으면 전체를 돌려준다")
    void controllerPaginationContract() {
        UUID admin = UUID.randomUUID();
        when(guardClient.check(eq("system"), eq("doro"), eq("admin"), anyString())).thenReturn(true);
        newUserWithSession();

        assertThatThrownBy(() -> adminController.getAllUsers(admin, 0, 501, null, null)).isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> adminController.getAllUsers(admin, 0, 0, null, null)).isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> adminController.getAllUsers(admin, -1, 10, null, null)).isInstanceOf(AuthException.class);

        ResponseEntity<ApiResponse<List<UserProfileResponse>>> paged = adminController.getAllUsers(admin, 0, 1, null, null);
        assertThat(paged.getHeaders().getFirst("X-Total-Count")).isNotNull();
        assertThat(paged.getBody().getData()).hasSize(1);

        ResponseEntity<ApiResponse<List<UserProfileResponse>>> all = adminController.getAllUsers(admin, null, null, null, null);
        assertThat(all.getHeaders().getFirst("X-Total-Count")).isNull();
        assertThat(all.getBody().getData().size()).isGreaterThanOrEqualTo(1);
    }
}

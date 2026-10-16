package com.hunnit_beasts.auth;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.domain.auth.dto.SignUpRequest;
import com.hunnit_beasts.auth.domain.auth.service.AuthService;
import com.hunnit_beasts.auth.domain.user.entity.UserRole;
import com.hunnit_beasts.auth.domain.user.service.UserService;
import com.hunnit_beasts.auth.infrastructure.guard.GuardClient;
import com.hunnit_beasts.auth.infrastructure.guard.GuardClient.TupleDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 역할 변경은 DB 역할과 Guard 튜플이 함께 바뀌어야 한다. Guard 호출이 조용히 실패하면 강등한 사용자의 관리자 튜플이
 * 남아 권한이 유지되므로(DB 는 USER, Guard 는 admin), 실패를 예외로 알리고 DB 변경도 되돌린다.
 */
@SpringBootTest
@ActiveProfiles("test")
class RoleChangeGuardSyncTest {

    @Autowired private AuthService authService;
    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private GuardClient guardClient;

    private UUID newUser() {
        UUID id = authService.signup(new SignUpRequest("rolesync-" + UUID.randomUUID() + "@doro.local", "Password123!", "Role Sync"));
        // 가입 때 나간 Guard 호출은 각 테스트의 검증 대상이 아니다
        clearInvocations(guardClient);
        return id;
    }

    private String roleOf(UUID id) {
        return jdbc.queryForObject("select role from users where id = ?", String.class, id);
    }

    private void adminMayManageRoles() {
        when(guardClient.check(eq("system"), eq("doro"), eq("manage_roles"), anyString())).thenReturn(true);
    }

    @Test
    @DisplayName("Guard 의 오래된 튜플 삭제가 실패하면 역할 변경은 503 이고 DB 역할도 바뀌지 않는다")
    void staleTupleDeleteFailureRollsBack() {
        UUID target = newUser();
        adminMayManageRoles();
        doThrow(new IllegalStateException("guard down")).when(guardClient).deleteTuplesOrThrow(any());

        assertThatThrownBy(() -> userService.changeUserRole(target, UserRole.ADMIN, UUID.randomUUID()))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.GUARD_UNAVAILABLE));
        assertThat(roleOf(target)).isEqualTo("USER");
        // 보안: 승급 중 삭제가 실패해도 새 관리자 튜플은 쓰이지 않는다(DB 는 USER 로 롤백되는데 Guard 에 관리자 튜플만 남는 상태가 없다)
        verify(guardClient, never()).writeTuplesOrThrow(any());
    }

    @Test
    @DisplayName("새 튜플 등록이 실패해도 역할 변경은 503 이고 DB 역할은 그대로다")
    void tupleWriteFailureRollsBack() {
        UUID target = newUser();
        adminMayManageRoles();
        doThrow(new IllegalStateException("guard down")).when(guardClient).writeTuplesOrThrow(any());

        assertThatThrownBy(() -> userService.changeUserRole(target, UserRole.ADMIN, UUID.randomUUID()))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.GUARD_UNAVAILABLE));
        assertThat(roleOf(target)).isEqualTo("USER");
    }

    @Test
    @DisplayName("성공하면 잃는 권한의 튜플만 먼저 지우고 새 역할의 튜플을 쓰며, DB 역할이 바뀐다")
    @SuppressWarnings("unchecked")
    void successDeletesLostTuplesThenWritesNewOnes() {
        UUID target = newUser();
        adminMayManageRoles();

        userService.changeUserRole(target, UserRole.ADMIN, UUID.randomUUID());

        assertThat(roleOf(target)).isEqualTo("ADMIN");
        var order = inOrder(guardClient);
        ArgumentCaptor<List<TupleDto>> deleted = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<TupleDto>> written = ArgumentCaptor.forClass(List.class);
        order.verify(guardClient).deleteTuplesOrThrow(deleted.capture());
        order.verify(guardClient).writeTuplesOrThrow(written.capture());
        String id = target.toString();
        // ADMIN 이 되면 최고 관리자 멤버십은 가질 수 없으므로 그 튜플은 지워진다
        assertThat(deleted.getValue()).contains(TupleDto.of("system", "doro", "super_admin", "user", id));
        assertThat(written.getValue()).contains(TupleDto.of("system", "doro", "admin", "user", id));
        // 새 역할이 계속 쓰는 튜플은 지웠다 다시 쓰지 않는다(그 사이에 권한이 비는 순간이 없도록)
        assertThat(deleted.getValue()).doesNotContainAnyElementsOf(written.getValue());
    }
}

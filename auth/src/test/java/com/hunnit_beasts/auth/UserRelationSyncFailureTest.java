package com.hunnit_beasts.auth;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.domain.auth.dto.SignUpRequest;
import com.hunnit_beasts.auth.domain.auth.service.AuthService;
import com.hunnit_beasts.auth.domain.user.entity.User;
import com.hunnit_beasts.auth.domain.user.entity.UserRole;
import com.hunnit_beasts.auth.domain.user.entity.UserStatus;
import com.hunnit_beasts.auth.domain.user.repository.UserRepository;
import com.hunnit_beasts.auth.domain.user.service.UserRelationSyncService;
import com.hunnit_beasts.auth.infrastructure.guard.GuardClient;
import com.hunnit_beasts.auth.infrastructure.guard.GuardClient.TupleDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Guard 튜플 동기화의 실패 방향: 쓰기 실패 시 아무것도 지우지 않고, 삭제 실패는 숨기지 않으며, 기동 동기화는 실패를 세어 계속한다. */
@SpringBootTest
@ActiveProfiles("test")
class UserRelationSyncFailureTest {

    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserRelationSyncService syncService;
    @MockitoBean private GuardClient guardClient;

    @BeforeEach
    void resetMock() {
        reset(guardClient);
    }

    private User newUser() {
        UUID id = authService.signup(new SignUpRequest("tuplesync-" + UUID.randomUUID() + "@doro.local", "Password123!", "Tuple Sync"));
        reset(guardClient);
        return userRepository.findById(id).orElseThrow();
    }

    @Test
    @DisplayName("삭제가 실패하면 아무 튜플도 쓰지 않고 GUARD_UNAVAILABLE 을 던진다(승급 중에도 새 관리자 튜플이 남지 않는다)")
    void deleteFailureWritesNothing() {
        User user = newUser();
        doThrow(new IllegalStateException("guard down")).when(guardClient).deleteTuplesOrThrow(any());

        assertThatThrownBy(() -> syncService.syncUserTuplesOrThrow(user, UserRole.ADMIN))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.GUARD_UNAVAILABLE));
        verify(guardClient, never()).writeTuplesOrThrow(any());
    }

    @Test
    @DisplayName("삭제 뒤 쓰기가 실패하면 오류가 호출 측에 전달된다(Guard 는 권한이 적은 쪽으로만 어긋나 안전하고 기동 동기화가 복구한다)")
    @SuppressWarnings("unchecked")
    void writeFailureAfterDeleteIsSurfaced() {
        User user = newUser();
        doThrow(new IllegalStateException("guard down")).when(guardClient).writeTuplesOrThrow(any());

        assertThatThrownBy(() -> syncService.syncUserTuplesOrThrow(user, UserRole.USER))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.GUARD_UNAVAILABLE));
        ArgumentCaptor<List<TupleDto>> deleted = ArgumentCaptor.forClass(List.class);
        verify(guardClient).deleteTuplesOrThrow(deleted.capture());
        assertThat(deleted.getValue()).contains(
                TupleDto.of("system", "doro", "admin", "user", user.getId().toString()));
    }

    @Test
    @DisplayName("멱등: 같은 역할로 두 번 맞춰도 같은 쓰기/삭제 목록을 보낸다")
    @SuppressWarnings("unchecked")
    void syncIsIdempotent() {
        User user = newUser();

        syncService.syncUserTuplesOrThrow(user, UserRole.ADMIN);
        syncService.syncUserTuplesOrThrow(user, UserRole.ADMIN);

        ArgumentCaptor<List<TupleDto>> written = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<TupleDto>> deleted = ArgumentCaptor.forClass(List.class);
        verify(guardClient, times(2)).writeTuplesOrThrow(written.capture());
        verify(guardClient, times(2)).deleteTuplesOrThrow(deleted.capture());
        assertThat(written.getAllValues().get(0)).isEqualTo(written.getAllValues().get(1));
        assertThat(deleted.getAllValues().get(0)).isEqualTo(deleted.getAllValues().get(1));
    }

    @Test
    @DisplayName("가입은 Guard 가 실패해도 계정을 만들고(best-effort) 예외를 던지지 않는다")
    void signupSurvivesGuardFailure() {
        doThrow(new IllegalStateException("guard down")).when(guardClient).writeTuplesOrThrow(any());

        UUID id = authService.signup(new SignUpRequest("tuplesync-" + UUID.randomUUID() + "@doro.local", "Password123!", "Tuple Sync"));

        assertThat(userRepository.findById(id)).isPresent();
        verify(guardClient, atLeastOnce()).writeTuplesOrThrow(anyList());
    }

    @Test
    @DisplayName("기동 동기화는 한 명이 실패해도 나머지를 계속 맞추고 예외를 던지지 않는다")
    @SuppressWarnings("unchecked")
    void startupSyncContinuesAfterOneFailure() {
        User bad = newUser();
        newUser();
        newUser();
        int active = (int) userRepository.findAll().stream().filter(u -> u.getStatus() != UserStatus.DELETED).count();
        String badId = bad.getId().toString();
        doAnswer(inv -> {
            List<TupleDto> tuples = inv.getArgument(0);
            if (tuples.toString().contains(badId)) {
                throw new IllegalStateException("guard down for one user");
            }
            return tuples.size();
        }).when(guardClient).writeTuplesOrThrow(anyList());

        syncService.syncAllUsersOnStartup();

        // 실패한 한 명 때문에 멈추지 않고 모든 활성 사용자에게 삭제·쓰기를 시도했다
        verify(guardClient, times(active)).deleteTuplesOrThrow(anyList());
        verify(guardClient, times(active)).writeTuplesOrThrow(anyList());
    }

    @Test
    @DisplayName("best-effort 동기화는 성공하면 true, 실패하면 false 를 돌려준다")
    void bestEffortReturnsOutcome() {
        User user = newUser();
        assertThat(syncService.syncUserTuplesBestEffort(user, UserRole.USER)).isTrue();
        doThrow(new IllegalStateException("guard down")).when(guardClient).writeTuplesOrThrow(any());
        assertThat(syncService.syncUserTuplesBestEffort(user, UserRole.USER)).isFalse();
    }
}

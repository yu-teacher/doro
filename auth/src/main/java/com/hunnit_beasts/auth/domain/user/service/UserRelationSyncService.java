package com.hunnit_beasts.auth.domain.user.service;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.common.log.LogMasking;
import com.hunnit_beasts.auth.domain.user.entity.User;
import com.hunnit_beasts.auth.domain.user.entity.UserRole;
import com.hunnit_beasts.auth.domain.user.entity.UserStatus;
import com.hunnit_beasts.auth.domain.user.repository.UserRepository;
import com.hunnit_beasts.auth.infrastructure.guard.GuardClient;
import com.hunnit_beasts.auth.infrastructure.guard.GuardClient.TupleDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserRelationSyncService {

    private final GuardClient guardClient;
    private final UserRepository userRepository;

    /** 사용자 역할에서 파생되는 모든 Guard 튜플(시스템 멤버십 + 이 사용자를 대상으로 한 관리 관계). */
    private static List<TupleDto> roleTuples(String userId) {
        return List.of(
                TupleDto.of("system", "doro", "super_admin", "user", userId),
                TupleDto.of("system", "doro", "admin", "user", userId),
                TupleDto.of("user", userId, "manager", "system", "doro", "admin"),
                TupleDto.of("user", userId, "super_manager", "system", "doro", "super_admin")
        );
    }

    /** 영구 탈퇴 시 사용자의 IAM 튜플을 모두 지운다. Guard 장애는 예외로 전파해 호출 측이 다시 시도하게 한다. */
    public void removeUserTuplesOrThrow(UUID userId) {
        guardClient.deleteTuplesOrThrow(roleTuples(userId.toString()));
    }

    /** 역할별로 가져야 하는 Guard 튜플. */
    private static List<TupleDto> tuplesFor(UserRole role, String userId) {
        List<TupleDto> tuples = new ArrayList<>();
        if (role == UserRole.SUPER_ADMIN) {
            // 시스템 최고 관리자 멤버십
            tuples.add(TupleDto.of("system", "doro", "super_admin", "user", userId));
            // 최고 관리자 대상 객체는 super_manager만 등록 (일반 admin이 2FA 취소 불가)
            tuples.add(TupleDto.of("user", userId, "super_manager", "system", "doro", "super_admin"));
        } else if (role == UserRole.ADMIN) {
            // 시스템 관리자 멤버십
            tuples.add(TupleDto.of("system", "doro", "admin", "user", userId));
            // 일반 관리자 대상 객체는 admin 및 super_admin 모두 관리 가능
            tuples.add(TupleDto.of("user", userId, "manager", "system", "doro", "admin"));
            tuples.add(TupleDto.of("user", userId, "super_manager", "system", "doro", "super_admin"));
        } else {
            // 일반 회원 (USER) 대상 객체: admin 및 super_admin 모두 2FA 취소/관리 가능
            tuples.add(TupleDto.of("user", userId, "manager", "system", "doro", "admin"));
            tuples.add(TupleDto.of("user", userId, "super_manager", "system", "doro", "super_admin"));
        }
        return tuples;
    }

    /**
     * 역할 변경용 동기화. {@link #syncUserTuples} 와 달리 Guard 실패를 삼키지 않는다: 실패하면 GUARD_UNAVAILABLE 로
     * 알려, 호출 측 트랜잭션이 DB 의 역할 변경을 되돌리게 한다(강등했는데 Guard 에 관리자 튜플이 남는 상태를 막는다).
     * 새 역할이 계속 쓰는 튜플은 건드리지 않고, 잃는 권한의 튜플만 먼저 지운 뒤 새 튜플을 쓴다.
     * 중간에 실패해도 줄어드는 쪽(권한 부족)으로만 어긋나며, 기동 시 전체 동기화가 이를 복구한다.
     */
    public void syncUserTuplesOrThrow(User user, UserRole newRole) {
        String userId = user.getId().toString();
        List<TupleDto> tuplesToWrite = tuplesFor(newRole, userId);
        List<TupleDto> staleTuples = roleTuples(userId).stream()
                .filter(tuple -> !tuplesToWrite.contains(tuple))
                .toList();
        try {
            guardClient.deleteTuplesOrThrow(staleTuples);
            guardClient.writeTuplesOrThrow(tuplesToWrite);
        } catch (RuntimeException e) {
            log.error("Failed to synchronize Guard tuples for a role change: userId={}, newRole={}", user.getId(), newRole, e);
            throw new AuthException(ErrorCode.GUARD_UNAVAILABLE);
        }
        log.info("Synchronized Guard tuples for a role change: userId={}, newRole={}, deleted={}, written={}",
                user.getId(), newRole, staleTuples.size(), tuplesToWrite.size());
    }

    /**
     * 사용자 권한/역할 변경에 따른 Zanzibar 튜플 동기화
     */
    public void syncUserTuples(User user, UserRole newRole) {
        String userId = user.getId().toString();
        log.info("Synchronizing Zanzibar relation tuples for user: email={}, newRole={}",
                LogMasking.maskEmail(user.getEmail()), newRole);

        // 1. 기존 잠재적 상위 권한 튜플 정리 (안전한 교체)
        List<TupleDto> tuplesToDelete = roleTuples(userId);
        guardClient.deleteTuples(tuplesToDelete);

        // 2. 신규 역할에 따른 정규 튜플 등록
        List<TupleDto> tuplesToWrite = tuplesFor(newRole, userId);

        int written = guardClient.writeTuples(tuplesToWrite);
        log.info("Successfully synchronized {} Zanzibar relation tuples for user {}",
                written, LogMasking.maskEmail(user.getEmail()));
    }

    /**
     * 애플리케이션 초기 기동 시 전체 DB 유저의 Zanzibar 관계 튜플 자동 복구/동기화
     */
    @EventListener(ApplicationReadyEvent.class)
    public void syncAllUsersOnStartup() {
        log.info("Starting initial Zanzibar ReBAC relation tuple synchronization for existing users...");
        try {
            List<User> allUsers = userRepository.findAll();
            for (User user : allUsers) {
                // 영구 탈퇴한 계정의 튜플을 되살리지 않는다.
                if (user.getStatus() == UserStatus.DELETED) {
                    continue;
                }
                syncUserTuples(user, user.getRole() != null ? user.getRole() : UserRole.USER);
            }
            log.info("Completed initial Zanzibar relation tuple synchronization for {} users.", allUsers.size());
        } catch (Exception e) {
            log.warn("Could not synchronize Zanzibar tuples on startup (guard-api might be warming up): {}", e.getMessage());
        }
    }
}

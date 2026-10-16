package com.hunnit_beasts.auth.domain.user.service;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
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
     * 사용자의 Guard 튜플을 역할에 맞게 맞춘다(멱등). Guard 실패를 삼키지 않고 GUARD_UNAVAILABLE 로 알린다.
     * 순서는 "잃는 권한의 튜플만 먼저 삭제 -> 새 튜플 쓰기" 다. 이 순서에서는 중간에 실패해도 Guard 가 DB 보다 **권한이 적은 쪽**으로만 어긋난다:
     * 삭제가 실패하면 아무것도 쓰지 않았으므로(승급 때 새 관리자 튜플만 남는 상태가 생기지 않는다) 호출 측 트랜잭션이 DB 역할 변경을 되돌리고,
     * 삭제 뒤 쓰기가 실패하면 DB 는 되돌아가지만 Guard 는 권한이 부족한 상태라 안전하며 기동 시 전체 동기화가 복구한다.
     * 반대 순서("쓰기 먼저")는 승급 중 삭제가 실패하면 DB 는 롤백되는데 Guard 에 높은 권한이 남아 권한이 늘어난 채 어긋나므로 쓰지 않는다.
     * 새 역할이 계속 쓰는 튜플은 지웠다 다시 쓰지 않는다.
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
            log.error("Failed to synchronize Guard tuples: userId={}, role={}", user.getId(), newRole, e);
            throw new AuthException(ErrorCode.GUARD_UNAVAILABLE);
        }
        log.info("Synchronized Guard tuples: userId={}, role={}, deleted={}, written={}",
                user.getId(), newRole, staleTuples.size(), tuplesToWrite.size());
    }

    /**
     * 사용자 작업 자체는 이미 끝났고 튜플은 나중에 복구해도 되는 경로(가입, 탈퇴 유예 복구)용. 실패해도 예외를 던지지 않고
     * ERROR 로그(사용자 ID 만)를 남긴 뒤 false 를 돌려준다. 실제 동기화는 {@link #syncUserTuplesOrThrow} 한 곳에서 한다.
     * 복구는 기동 시 전체 동기화({@link #syncAllUsersOnStartup})가 맡는다.
     * 역할 변경처럼 실패를 숨기면 안 되는 경로에서는 쓰지 않는다.
     */
    public boolean syncUserTuplesBestEffort(User user, UserRole role) {
        try {
            syncUserTuplesOrThrow(user, role);
            return true;
        } catch (AuthException e) {
            log.error("Guard tuple sync failed and was deferred to the next startup sync: userId={}, role={}", user.getId(), role);
            return false;
        }
    }

    /**
     * 애플리케이션 초기 기동 시 전체 DB 유저의 Zanzibar 관계 튜플 자동 복구/동기화
     */
    @EventListener(ApplicationReadyEvent.class)
    public void syncAllUsersOnStartup() {
        log.info("Starting initial Zanzibar ReBAC relation tuple synchronization for existing users...");
        List<User> allUsers;
        try {
            allUsers = userRepository.findAll();
        } catch (Exception e) {
            log.warn("Could not load users for the startup tuple synchronization: {}", e.getMessage());
            return;
        }
        int synced = 0;
        int failed = 0;
        for (User user : allUsers) {
            // 영구 탈퇴한 계정의 튜플을 되살리지 않는다.
            if (user.getStatus() == UserStatus.DELETED) {
                continue;
            }
            // 한 명이 실패해도 나머지는 계속 맞춘다. 실패한 사용자 ID 는 syncUserTuplesBestEffort 가 ERROR 로 남긴다.
            if (syncUserTuplesBestEffort(user, user.getRole() != null ? user.getRole() : UserRole.USER)) {
                synced++;
            } else {
                failed++;
            }
        }
        if (failed > 0) {
            log.error("Startup Zanzibar tuple synchronization finished with failures: synced={}, failed={}, total={}",
                    synced, failed, allUsers.size());
        } else {
            log.info("Completed initial Zanzibar relation tuple synchronization: synced={}, total={}", synced, allUsers.size());
        }
    }
}

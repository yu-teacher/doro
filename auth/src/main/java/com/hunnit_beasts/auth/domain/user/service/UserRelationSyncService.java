package com.hunnit_beasts.auth.domain.user.service;

import com.hunnit_beasts.auth.domain.user.entity.User;
import com.hunnit_beasts.auth.domain.user.entity.UserRole;
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

    /**
     * 사용자 권한/역할 변경에 따른 Zanzibar 튜플 동기화
     */
    public void syncUserTuples(User user, UserRole newRole) {
        String userId = user.getId().toString();
        log.info("Synchronizing Zanzibar relation tuples for user: email={}, newRole={}", user.getEmail(), newRole);

        // 1. 기존 잠재적 상위 권한 튜플 정리 (안전한 교체)
        List<TupleDto> tuplesToDelete = List.of(
                TupleDto.of("system", "doro", "super_admin", "user", userId),
                TupleDto.of("system", "doro", "admin", "user", userId),
                TupleDto.of("user", userId, "manager", "system", "doro", "admin"),
                TupleDto.of("user", userId, "super_manager", "system", "doro", "super_admin")
        );
        guardClient.deleteTuples(tuplesToDelete);

        // 2. 신규 역할에 따른 정규 튜플 등록
        List<TupleDto> tuplesToWrite = new ArrayList<>();

        if (newRole == UserRole.SUPER_ADMIN) {
            // 시스템 최고 관리자 멤버십
            tuplesToWrite.add(TupleDto.of("system", "doro", "super_admin", "user", userId));
            // 최고 관리자 대상 객체는 super_manager만 등록 (일반 admin이 2FA 취소 불가)
            tuplesToWrite.add(TupleDto.of("user", userId, "super_manager", "system", "doro", "super_admin"));
        } else if (newRole == UserRole.ADMIN) {
            // 시스템 관리자 멤버십
            tuplesToWrite.add(TupleDto.of("system", "doro", "admin", "user", userId));
            // 일반 관리자 대상 객체는 admin 및 super_admin 모두 관리 가능
            tuplesToWrite.add(TupleDto.of("user", userId, "manager", "system", "doro", "admin"));
            tuplesToWrite.add(TupleDto.of("user", userId, "super_manager", "system", "doro", "super_admin"));
        } else {
            // 일반 회원 (USER) 대상 객체: admin 및 super_admin 모두 2FA 취소/관리 가능
            tuplesToWrite.add(TupleDto.of("user", userId, "manager", "system", "doro", "admin"));
            tuplesToWrite.add(TupleDto.of("user", userId, "super_manager", "system", "doro", "super_admin"));
        }

        int written = guardClient.writeTuples(tuplesToWrite);
        log.info("Successfully synchronized {} Zanzibar relation tuples for user {}", written, user.getEmail());
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
                syncUserTuples(user, user.getRole() != null ? user.getRole() : UserRole.USER);
            }
            log.info("Completed initial Zanzibar relation tuple synchronization for {} users.", allUsers.size());
        } catch (Exception e) {
            log.warn("Could not synchronize Zanzibar tuples on startup (guard-api might be warming up): {}", e.getMessage());
        }
    }
}

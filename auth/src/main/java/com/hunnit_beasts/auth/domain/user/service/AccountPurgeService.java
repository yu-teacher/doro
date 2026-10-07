package com.hunnit_beasts.auth.domain.user.service;

import com.hunnit_beasts.auth.domain.credential.repository.CredentialRepository;
import com.hunnit_beasts.auth.domain.session.repository.UserSessionRepository;
import com.hunnit_beasts.auth.domain.user.entity.UserRole;
import com.hunnit_beasts.auth.domain.user.entity.UserStatus;
import com.hunnit_beasts.auth.domain.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 유예가 끝난 탈퇴 계정의 개인정보를 영구적으로 익명화한다.
 * <p>
 * 한 계정당 순서: ① Guard 튜플 삭제(실패하면 상태를 바꾸지 않고 다음 주기에 다시 시도) → ② 한 트랜잭션에서
 * 조건부 UPDATE 로 익명화 → 성공했을 때만 자격증명·세션 흔적 삭제. ②가 조건부라서 같은 순간에 사용자가 로그인해
 * 복구하면 둘 중 먼저 실행된 쪽만 이긴다. 복구가 이기면 ①에서 지운 튜플은 복구 쪽이 다시 만든다.
 */
@Slf4j
@Service
public class AccountPurgeService {

    static final String DELETED_NAME = "탈퇴한 사용자";
    static final String PLACEHOLDER_EMAIL_FORMAT = "deleted-%s@deleted.invalid";

    private final UserRepository userRepository;
    private final CredentialRepository credentialRepository;
    private final UserSessionRepository sessionRepository;
    private final UserRelationSyncService userRelationSyncService;
    private final TransactionTemplate transaction;

    @Value("${doro.iam.account-deletion.grace-days:30}")
    private long graceDays;

    @Value("${doro.iam.account-deletion.purge-batch-size:100}")
    private int batchSize;

    public AccountPurgeService(UserRepository userRepository,
                               CredentialRepository credentialRepository,
                               UserSessionRepository sessionRepository,
                               UserRelationSyncService userRelationSyncService,
                               PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.sessionRepository = sessionRepository;
        this.userRelationSyncService = userRelationSyncService;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${doro.iam.account-deletion.purge-interval-ms:3600000}",
            initialDelayString = "${doro.iam.account-deletion.purge-initial-delay-ms:120000}")
    public void purgeDueAccounts() {
        purgeDueAccounts(Instant.now());
    }

    /** @return 이번 호출에서 익명화한 계정 수 */
    public int purgeDueAccounts(Instant now) {
        Instant cutoff = now.minus(Duration.ofDays(graceDays));
        List<UUID> due = userRepository.findIdsDeletionRequestedBefore(
                UserStatus.PENDING_DELETION, cutoff, PageRequest.ofSize(batchSize));
        int purged = 0;
        for (UUID userId : due) {
            try {
                if (purgeOne(userId, cutoff, now)) {
                    purged++;
                }
            } catch (Exception e) {
                // 한 계정의 실패(Guard 장애 등)가 나머지를 막지 않게 하고, 상태가 그대로이므로 다음 주기에 다시 시도한다.
                log.error("Account purge failed, will retry on the next run: userId={}", userId, e);
            }
        }
        if (!due.isEmpty()) {
            log.info("Account purge finished: due={}, purged={}", due.size(), purged);
        }
        return purged;
    }

    private boolean purgeOne(UUID userId, Instant cutoff, Instant now) {
        userRelationSyncService.removeUserTuplesOrThrow(userId);

        Boolean purged = transaction.execute(status -> {
            int updated = userRepository.anonymizeIfDue(
                    userId,
                    PLACEHOLDER_EMAIL_FORMAT.formatted(userId),
                    DELETED_NAME,
                    UserStatus.DELETED,
                    UserStatus.PENDING_DELETION,
                    cutoff,
                    // 사용자마다 처리 시각을 따로 기록해, 서브 서비스의 증분 조회가 같은 시각의 묶음에서 끊기지 않게 한다.
                    Instant.now());
            if (updated == 0) {
                return false;
            }
            credentialRepository.deleteByUserId(userId);
            sessionRepository.deleteAllByUserId(userId);
            return true;
        });

        if (Boolean.TRUE.equals(purged)) {
            log.info("Account anonymized after the grace period: userId={}", userId);
            return true;
        }
        // 튜플을 지우는 사이 사용자가 복구했다면 복구 쪽이 이미 튜플을 다시 만들었을 수 있다. 어느 쪽이든 되살려 둔다.
        userRepository.findById(userId)
                .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                .ifPresent(u -> userRelationSyncService.syncUserTuples(
                        u, u.getRole() != null ? u.getRole() : UserRole.USER));
        log.info("Account purge skipped because the account was restored: userId={}", userId);
        return false;
    }
}

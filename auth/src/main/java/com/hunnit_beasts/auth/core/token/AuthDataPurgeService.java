package com.hunnit_beasts.auth.core.token;

import com.hunnit_beasts.auth.domain.session.repository.UserSessionRepository;
import com.hunnit_beasts.auth.domain.token.repository.RefreshTokenRepository;
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
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * 만료·폐기된 리프레시 토큰과 비활성 세션을 보존 기간이 지난 뒤 삭제한다(테이블이 끝없이 커지는 것을 막는다).
 * <p>
 * 보존 기간은 리프레시 토큰 유효기간보다 길어야 한다. 폐기된 토큰의 행이 남아 있어야 재사용 탐지가 동작하기 때문이다.
 * 한 번에 지우는 행 수를 batch-size 로 제한하고 배치마다 트랜잭션을 나눠, 오래 락을 잡지 않는다.
 */
@Slf4j
@Service
public class AuthDataPurgeService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserSessionRepository sessionRepository;
    private final TransactionTemplate transaction;

    @Value("${doro.iam.token-purge.enabled:true}")
    private boolean enabled;

    @Value("${doro.iam.token-purge.retention-days:90}")
    private long retentionDays;

    @Value("${doro.iam.token-purge.batch-size:1000}")
    private int batchSize;

    /** 한 번 실행에서 처리할 최대 배치 수(무한 루프 방지). */
    @Value("${doro.iam.token-purge.max-batches-per-run:100}")
    private int maxBatchesPerRun;

    public AuthDataPurgeService(RefreshTokenRepository refreshTokenRepository,
                                UserSessionRepository sessionRepository,
                                PlatformTransactionManager transactionManager) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.sessionRepository = sessionRepository;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${doro.iam.token-purge.interval-ms:3600000}",
            initialDelayString = "${doro.iam.token-purge.initial-delay-ms:300000}")
    public void purgeScheduled() {
        if (enabled) {
            purge(Instant.now());
        }
    }

    /** @return {삭제한 토큰 수, 삭제한 세션 수} */
    public int[] purge(Instant now) {
        Instant cutoff = now.minus(Duration.ofDays(retentionDays));
        int size = Math.max(1, batchSize);
        int tokens = deleteInBatches(size,
                () -> refreshTokenRepository.findIdsPurgeableBefore(cutoff, PageRequest.ofSize(size)),
                refreshTokenRepository::deleteAllByIdIn);
        int sessions = deleteInBatches(size,
                () -> sessionRepository.findInactiveIdsLastActiveBefore(cutoff, PageRequest.ofSize(size)),
                ids -> {
                    // 운영 DB 는 FK ON DELETE CASCADE 로도 지워지지만, 스키마가 다른 환경에서도 고아가 남지 않게 명시적으로 지운다.
                    refreshTokenRepository.deleteAllBySessionIdIn(ids);
                    return sessionRepository.deleteAllByIdIn(ids);
                });
        if (tokens > 0 || sessions > 0) {
            log.info("Purged stale auth data: refreshTokens={}, sessions={}", tokens, sessions);
        }
        return new int[]{tokens, sessions};
    }

    private int deleteInBatches(int size, Supplier<List<UUID>> finder, ToIntFunction<List<UUID>> deleter) {
        int total = 0;
        for (int i = 0; i < maxBatchesPerRun; i++) {
            Integer deleted = transaction.execute(status -> {
                List<UUID> ids = finder.get();
                return ids.isEmpty() ? 0 : deleter.applyAsInt(ids);
            });
            int n = deleted == null ? 0 : deleted;
            total += n;
            if (n < size) {
                break;
            }
        }
        return total;
    }
}

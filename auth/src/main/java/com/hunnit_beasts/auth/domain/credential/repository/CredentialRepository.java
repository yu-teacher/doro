package com.hunnit_beasts.auth.domain.credential.repository;

import com.hunnit_beasts.auth.domain.credential.entity.Credential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CredentialRepository extends JpaRepository<Credential, UUID> {
    Optional<Credential> findByUserId(UUID userId);
    void deleteByUserId(UUID userId);

    /**
     * 동시 실패 요청에서도 카운트가 유실되지 않도록 DB 에서 원자적으로 증가시킨다.
     * 잠금 시간이 이미 지났다면 이전 실패는 잊고 1부터 다시 센다. 그러지 않으면 잠금이 풀린 직후의 첫 실패 한 번이
     * (DB 에 남은 카운터 5 가 6 이 되어) 곧바로 다시 잠그므로, 15분마다 요청 한 번으로 계정을 계속 잠가 둘 수 있다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Credential c SET "
            + "c.failedAttempts = CASE WHEN c.lockedUntil IS NOT NULL AND c.lockedUntil < :now THEN 1 ELSE c.failedAttempts + 1 END, "
            + "c.lockedUntil = CASE WHEN c.lockedUntil IS NOT NULL AND c.lockedUntil < :now THEN NULL ELSE c.lockedUntil END "
            + "WHERE c.userId = :userId")
    int incrementFailedAttempts(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Credential c SET c.lockedUntil = :lockedUntil WHERE c.userId = :userId AND c.failedAttempts >= :threshold")
    int lockIfThresholdReached(@Param("userId") UUID userId,
                               @Param("threshold") int threshold,
                               @Param("lockedUntil") Instant lockedUntil);
}

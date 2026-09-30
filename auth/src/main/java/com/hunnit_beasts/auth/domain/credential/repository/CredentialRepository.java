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

    /** 동시 실패 요청에서도 카운트가 유실되지 않도록 DB 에서 원자적으로 증가시킨다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Credential c SET c.failedAttempts = c.failedAttempts + 1 WHERE c.userId = :userId")
    int incrementFailedAttempts(@Param("userId") UUID userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Credential c SET c.lockedUntil = :lockedUntil WHERE c.userId = :userId AND c.failedAttempts >= :threshold")
    int lockIfThresholdReached(@Param("userId") UUID userId,
                               @Param("threshold") int threshold,
                               @Param("lockedUntil") Instant lockedUntil);
}

package com.hunnit_beasts.auth.domain.credential.repository;

import com.hunnit_beasts.auth.domain.credential.entity.Credential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CredentialRepository extends JpaRepository<Credential, UUID> {
    Optional<Credential> findByUserId(UUID userId);

    /** 여러 사용자의 credential 을 한 번에 조회한다(목록 화면의 N+1 방지). */
    List<Credential> findAllByUserIdIn(Collection<UUID> userIds);
    void deleteByUserId(UUID userId);

    /**
     * 계정 전체 잠금을 기록한다(여러 IP 의 실패가 합쳐 한도에 닿았을 때). 관리자 목록의 "잠김" 표시와 잠금 해제 API 가 이 값을 쓴다.
     * 이미 더 늦게까지 잠겨 있으면 줄이지 않는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Credential c SET c.lockedUntil = :lockedUntil, c.failedAttempts = :failures "
            + "WHERE c.userId = :userId AND (c.lockedUntil IS NULL OR c.lockedUntil < :lockedUntil)")
    int lockAccountUntil(@Param("userId") UUID userId,
                         @Param("lockedUntil") Instant lockedUntil,
                         @Param("failures") int failures);
}

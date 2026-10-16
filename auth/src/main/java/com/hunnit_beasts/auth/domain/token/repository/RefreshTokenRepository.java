package com.hunnit_beasts.auth.domain.token.repository;

import com.hunnit_beasts.auth.domain.token.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    Optional<RefreshToken> findFirstByFamilyIdAndIsRevokedFalseOrderByCreatedAtDesc(UUID familyId);

    @Modifying
    @Query("UPDATE RefreshToken r SET r.isRevoked = true WHERE r.familyId = :familyId")
    void revokeAllByFamilyId(@Param("familyId") UUID familyId);

    @Modifying
    @Query("UPDATE RefreshToken r SET r.isRevoked = true WHERE r.sessionId = :sessionId")
    void revokeAllBySessionId(@Param("sessionId") UUID sessionId);

    /** 아직 폐기되지 않은 경우에만 폐기한다. 갱신된 행 수(0 또는 1)로 동시 회전 경합의 승자를 가린다. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE RefreshToken r SET r.isRevoked = true WHERE r.id = :id AND r.isRevoked = false")
    int revokeIfActive(@Param("id") UUID id);

    /** 보존 기간이 지난 정리 대상: 만료 시각이 cutoff 이전이거나, 폐기된 뒤 발급 시각이 cutoff 이전인 토큰. */
    @Query("SELECT r.id FROM RefreshToken r WHERE r.expiresAt < :cutoff OR (r.isRevoked = true AND r.createdAt < :cutoff)")
    List<UUID> findIdsPurgeableBefore(@Param("cutoff") Instant cutoff, Pageable pageable);

    @Modifying
    @Query("DELETE FROM RefreshToken r WHERE r.id IN :ids")
    int deleteAllByIdIn(@Param("ids") List<UUID> ids);

    @Modifying
    @Query("DELETE FROM RefreshToken r WHERE r.sessionId IN :sessionIds")
    int deleteAllBySessionIdIn(@Param("sessionIds") List<UUID> sessionIds);
}

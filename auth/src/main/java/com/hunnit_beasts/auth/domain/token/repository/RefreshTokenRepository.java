package com.hunnit_beasts.auth.domain.token.repository;

import com.hunnit_beasts.auth.domain.token.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}

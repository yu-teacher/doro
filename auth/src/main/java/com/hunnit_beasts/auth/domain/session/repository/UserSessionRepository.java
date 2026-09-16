package com.hunnit_beasts.auth.domain.session.repository;

import com.hunnit_beasts.auth.domain.session.entity.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {
    List<UserSession> findByUserIdAndIsActiveTrueOrderByCreatedAtDesc(UUID userId);
    Optional<UserSession> findByIdAndIsActiveTrue(UUID id);

    @Query("SELECT MAX(s.userIndex) FROM UserSession s WHERE s.userId = :userId AND s.isActive = true")
    Optional<Integer> findMaxActiveUserIndex(@Param("userId") UUID userId);

    List<UserSession> findByUserIdAndIpAddressAndUserAgentAndIsActiveTrue(UUID userId, String ipAddress, String userAgent);

    @Modifying
    @Query("UPDATE UserSession s SET s.isActive = false WHERE s.userId = :userId")
    void deactivateAllByUserId(@Param("userId") UUID userId);

    @Modifying
    @Query("UPDATE UserSession s SET s.isActive = false WHERE s.userId = :userId AND s.id <> :currentSessionId AND s.isActive = true")
    void deactivateOtherSessions(@Param("userId") UUID userId, @Param("currentSessionId") UUID currentSessionId);

    @Modifying
    @Query("UPDATE UserSession s SET s.isActive = false WHERE s.expiresAt < :now AND s.isActive = true")
    int deactivateExpiredSessions(@Param("now") Instant now);
}

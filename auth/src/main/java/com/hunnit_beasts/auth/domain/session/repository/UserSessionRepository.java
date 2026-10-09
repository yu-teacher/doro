package com.hunnit_beasts.auth.domain.session.repository;

import com.hunnit_beasts.auth.domain.session.dto.SessionLiveness;
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

    /** 같은 User-Agent 값을 가진 활성 세션 전체. OAuth 세션은 클라이언트별 마커를 User-Agent 에 넣어 만들므로 클라이언트 단위 조회에 쓴다. */
    List<UserSession> findByUserAgentAndIsActiveTrue(String userAgent);

    List<UserSession> findByUserIdAndIpAddressAndUserAgentAndIsActiveTrue(UUID userId, String ipAddress, String userAgent);

    /** 영구 탈퇴 시 IP·User-Agent 등 세션 흔적을 지운다. 리프레시 토큰은 FK ON DELETE CASCADE 로 함께 삭제된다. */
    @Modifying
    @Query("DELETE FROM UserSession s WHERE s.userId = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);

    @Modifying
    @Query("UPDATE UserSession s SET s.isActive = false WHERE s.userId = :userId")
    void deactivateAllByUserId(@Param("userId") UUID userId);

    @Modifying
    @Query("UPDATE UserSession s SET s.isActive = false WHERE s.userId = :userId AND s.id <> :currentSessionId AND s.isActive = true")
    void deactivateOtherSessions(@Param("userId") UUID userId, @Param("currentSessionId") UUID currentSessionId);

    @Modifying
    @Query("UPDATE UserSession s SET s.isActive = false WHERE s.expiresAt < :now AND s.isActive = true")
    int deactivateExpiredSessions(@Param("now") Instant now);

    /**
     * 세션 생존 여부 판정에 필요한 컬럼만 단일 쿼리(세션 + 소유 사용자 조인)로 읽는다.
     * 엔티티를 로드하지 않으므로 세션을 갱신(touch)하지 않는다.
     */
    @Query("SELECT new com.hunnit_beasts.auth.domain.session.dto.SessionLiveness(s.isActive, s.expiresAt, u.status) "
            + "FROM UserSession s JOIN User u ON u.id = s.userId WHERE s.id = :sessionId")
    Optional<SessionLiveness> findLivenessById(@Param("sessionId") UUID sessionId);
}

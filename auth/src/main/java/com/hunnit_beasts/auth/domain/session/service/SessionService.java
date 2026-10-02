package com.hunnit_beasts.auth.domain.session.service;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.domain.session.dto.SessionLiveness;
import com.hunnit_beasts.auth.domain.session.dto.SessionResponse;
import com.hunnit_beasts.auth.domain.session.entity.UserSession;
import com.hunnit_beasts.auth.domain.session.repository.UserSessionRepository;
import com.hunnit_beasts.auth.domain.user.entity.UserStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    /** 동일 기기 재로그인으로 이전 세션을 대체할 때의 종료 사유 */
    static final String REASON_REPLACED_BY_NEW_LOGIN = "REPLACED_BY_NEW_LOGIN";
    /** 사용자별 최대 활성 세션 수를 넘겨 가장 오래된 세션을 종료할 때의 사유 */
    static final String REASON_SESSION_LIMIT_EXCEEDED = "SESSION_LIMIT_EXCEEDED";

    private final UserSessionRepository sessionRepository;
    private final SessionRevocationService sessionRevocationService;

    @Value("${doro.iam.session.inactivity-timeout-seconds:2592000}")
    private long inactivityTimeoutSeconds;

    /** 사용자당 동시 활성 세션 상한(새 세션 포함). 0 이하이면 무제한. */
    @Value("${doro.iam.session.max-active-sessions-per-user:10}")
    private int maxActiveSessionsPerUser;

    @Transactional
    public UserSession createSession(UUID userId, String deviceInfo, String ipAddress, String userAgent) {
        // 동일 기기 & 동일 브라우저(ipAddress + userAgent)의 이전 활성 세션 자동 정리 (다른 기기는 정상 유지)
        if (ipAddress != null && userAgent != null) {
            List<UserSession> existingSameDeviceSessions = sessionRepository
                    .findByUserIdAndIpAddressAndUserAgentAndIsActiveTrue(userId, ipAddress, userAgent);
            // 리프레시 토큰 폐기와 킬스위치 발행까지 한 흐름으로 처리하여 DB 와 Redis 상태를 일치시킨다.
            sessionRevocationService.revokeSessions(existingSameDeviceSessions, REASON_REPLACED_BY_NEW_LOGIN);
            if (!existingSameDeviceSessions.isEmpty()) {
                log.info("Revoked {} duplicate session(s) on the same device/browser: userId={}",
                        existingSameDeviceSessions.size(), userId);
            }
        }

        enforceSessionLimit(userId);

        int nextIndex = sessionRepository.findMaxActiveUserIndex(userId)
                .map(idx -> idx + 1)
                .orElse(0);

        Instant expiresAt = Instant.now().plus(inactivityTimeoutSeconds, ChronoUnit.SECONDS);

        String resolvedDeviceInfo = deviceInfo != null && !deviceInfo.isBlank() 
                ? deviceInfo 
                : parseDeviceInfo(userAgent);

        UserSession session = UserSession.builder()
                .userId(userId)
                .userIndex(nextIndex)
                .deviceInfo(resolvedDeviceInfo)
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .expiresAt(expiresAt)
                .build();

        return sessionRepository.save(session);
    }

    /** 새 세션을 포함해 활성 세션이 상한 이하가 되도록 가장 오래된 세션부터 종료한다. */
    private void enforceSessionLimit(UUID userId) {
        if (maxActiveSessionsPerUser <= 0) {
            return;
        }
        // 최신순 정렬이므로 뒤쪽이 가장 오래된 세션이다.
        List<UserSession> active = sessionRepository.findByUserIdAndIsActiveTrueOrderByCreatedAtDesc(userId);
        int excess = active.size() - (maxActiveSessionsPerUser - 1);
        if (excess <= 0) {
            return;
        }
        List<UserSession> oldest = active.subList(active.size() - excess, active.size());
        sessionRevocationService.revokeSessions(List.copyOf(oldest), REASON_SESSION_LIMIT_EXCEEDED);
        log.info("Revoked {} oldest session(s) over the per-user limit: userId={}, limit={}",
                oldest.size(), userId, maxActiveSessionsPerUser);
    }

    @Transactional(readOnly = true)
    public List<SessionResponse> getActiveSessions(UUID userId) {
        return sessionRepository.findByUserIdAndIsActiveTrueOrderByCreatedAtDesc(userId)
                .stream()
                .map(SessionResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public UserSession getActiveSession(UUID sessionId) {
        return sessionRepository.findByIdAndIsActiveTrue(sessionId)
                .orElseThrow(() -> new AuthException(ErrorCode.SESSION_NOT_FOUND));
    }

    @Transactional
    public void deactivateSession(UUID sessionId) {
        UserSession session = getActiveSession(sessionId);
        session.deactivate();
    }

    @Transactional
    public void deactivateOtherSessions(UUID userId, UUID currentSessionId) {
        sessionRepository.deactivateOtherSessions(userId, currentSessionId);
    }

    @Transactional
    public void deactivateAllSessions(UUID userId) {
        sessionRepository.deactivateAllByUserId(userId);
    }

    private String parseDeviceInfo(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Web Browser";
        }
        String os = "PC/Device";
        if (userAgent.contains("iPhone")) os = "iPhone";
        else if (userAgent.contains("iPad")) os = "iPad";
        else if (userAgent.contains("Android")) os = "Android Phone";
        else if (userAgent.contains("Macintosh") || userAgent.contains("Mac OS")) os = "Mac";
        else if (userAgent.contains("Windows")) os = "Windows PC";
        else if (userAgent.contains("Linux")) os = "Linux PC";

        String browser = "Browser";
        if (userAgent.contains("Chrome") && !userAgent.contains("Edg")) browser = "Chrome";
        else if (userAgent.contains("Edg")) browser = "Edge";
        else if (userAgent.contains("Safari") && !userAgent.contains("Chrome")) browser = "Safari";
        else if (userAgent.contains("Firefox")) browser = "Firefox";

        return os + " (" + browser + ")";
    }

    /**
     * 서브 서비스(SDK)의 폐기 확인용. DB(원본 진실)만 단일 쿼리로 조회하며 세션을 갱신하지 않는다.
     * 세션이 없거나 비활성/만료면 SESSION_EXPIRED(401), 소유 사용자가 ACTIVE 가 아니면 ACCOUNT_SUSPENDED(403).
     */
    @Transactional(readOnly = true)
    public void assertSessionLive(UUID sessionId) {
        if (sessionId == null) {
            throw new AuthException(ErrorCode.UNAUTHORIZED, "토큰에 세션 식별자가 없습니다.");
        }
        SessionLiveness liveness = sessionRepository.findLivenessById(sessionId)
                .orElseThrow(() -> new AuthException(ErrorCode.SESSION_EXPIRED));
        if (!liveness.active() || !liveness.expiresAt().isAfter(Instant.now())) {
            throw new AuthException(ErrorCode.SESSION_EXPIRED);
        }
        if (liveness.userStatus() != UserStatus.ACTIVE) {
            throw new AuthException(ErrorCode.ACCOUNT_SUSPENDED);
        }
    }
}

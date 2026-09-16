package com.hunnit_beasts.auth.domain.session.service;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.domain.session.dto.SessionResponse;
import com.hunnit_beasts.auth.domain.session.entity.UserSession;
import com.hunnit_beasts.auth.domain.session.repository.UserSessionRepository;
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

    private final UserSessionRepository sessionRepository;

    @Value("${doro.iam.session.inactivity-timeout-seconds:604800}")
    private long inactivityTimeoutSeconds;

    @Transactional
    public UserSession createSession(UUID userId, String deviceInfo, String ipAddress, String userAgent) {
        // 동일 기기 & 동일 브라우저(ipAddress + userAgent)의 이전 활성 세션 자동 정리 (다른 기기는 정상 유지)
        if (ipAddress != null && userAgent != null) {
            List<UserSession> existingSameDeviceSessions = sessionRepository
                    .findByUserIdAndIpAddressAndUserAgentAndIsActiveTrue(userId, ipAddress, userAgent);
            for (UserSession oldSession : existingSameDeviceSessions) {
                oldSession.deactivate();
                log.info("Deactivated duplicate session on the same device/browser: sessionId={}", oldSession.getId());
            }
        }

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
}

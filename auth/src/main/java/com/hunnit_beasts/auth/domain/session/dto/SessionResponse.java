package com.hunnit_beasts.auth.domain.session.dto;

import com.hunnit_beasts.auth.domain.session.entity.UserSession;

import java.time.Instant;
import java.util.UUID;

public record SessionResponse(
        UUID sessionId,
        UUID userId,
        int userIndex,
        String deviceInfo,
        String ipAddress,
        boolean isActive,
        Instant lastActiveAt,
        Instant expiresAt,
        Instant createdAt
) {
    public static SessionResponse from(UserSession session) {
        return new SessionResponse(
                session.getId(),
                session.getUserId(),
                session.getUserIndex(),
                session.getDeviceInfo(),
                session.getIpAddress(),
                session.isActive(),
                session.getLastActiveAt(),
                session.getExpiresAt(),
                session.getCreatedAt()
        );
    }
}

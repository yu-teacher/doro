package com.hunnit_beasts.auth.core.redis;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

public record KillSwitchEvent(
        String eventType,
        UUID userId,
        UUID sessionId,
        UUID familyId,
        String reason,
        Instant timestamp
) implements Serializable {
    public static KillSwitchEvent ofSessionRevoked(UUID userId, UUID sessionId, String reason) {
        return new KillSwitchEvent("SESSION_REVOKED", userId, sessionId, null, reason, Instant.now());
    }

    public static KillSwitchEvent ofFamilyRevoked(UUID userId, UUID familyId, String reason) {
        return new KillSwitchEvent("FAMILY_REVOKED", userId, null, familyId, reason, Instant.now());
    }
}

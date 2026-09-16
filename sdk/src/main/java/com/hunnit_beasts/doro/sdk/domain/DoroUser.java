package com.hunnit_beasts.doro.sdk.domain;

import java.util.UUID;

public record DoroUser(
        UUID userId,
        String email,
        UUID sessionId,
        int userIndex,
        String role
) {
    public DoroUser(UUID userId, String email, UUID sessionId, int userIndex) {
        this(userId, email, sessionId, userIndex, "USER");
    }

    public static DoroUser anonymous() {
        return new DoroUser(null, "anonymous", null, 0, "ANON");
    }

    public boolean isAuthenticated() {
        return userId != null;
    }

    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(role) || "SUPER_ADMIN".equalsIgnoreCase(role);
    }

    public boolean isSuperAdmin() {
        return "SUPER_ADMIN".equalsIgnoreCase(role);
    }
}

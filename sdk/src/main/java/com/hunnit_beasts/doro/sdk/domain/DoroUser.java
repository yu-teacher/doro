package com.hunnit_beasts.doro.sdk.domain;

import java.util.UUID;

/**
 * @param clientId OAuth 클라이언트가 받은 토큰(cid 클레임)이면 그 클라이언트 ID, 일반 로그인 토큰이면 null.
 *                 사용자 본인이 직접 로그인한 요청과 제3자 클라이언트를 대신한 요청을 구분하는 데 쓴다.
 */
public record DoroUser(
        UUID userId,
        String email,
        UUID sessionId,
        int userIndex,
        String role,
        String clientId
) {
    public DoroUser(UUID userId, String email, UUID sessionId, int userIndex, String role) {
        this(userId, email, sessionId, userIndex, role, null);
    }

    public DoroUser(UUID userId, String email, UUID sessionId, int userIndex) {
        this(userId, email, sessionId, userIndex, "USER", null);
    }

    /** OAuth 클라이언트 토큰으로 인증된 요청인가 (사용자가 직접 로그인한 요청이 아님). */
    public boolean isOAuthClientToken() {
        return clientId != null;
    }

    public static DoroUser anonymous() {
        return new DoroUser(null, "anonymous", null, 0, "ANON", null);
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

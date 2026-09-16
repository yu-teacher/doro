package com.hunnit_beasts.auth.domain.auth.dto;

import java.util.UUID;

public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UUID sessionId,
        int userIndex
) {
    public static TokenResponse of(String accessToken, String refreshToken, long expiresIn, UUID sessionId, int userIndex) {
        return new TokenResponse(accessToken, refreshToken, "Bearer", expiresIn, sessionId, userIndex);
    }
}

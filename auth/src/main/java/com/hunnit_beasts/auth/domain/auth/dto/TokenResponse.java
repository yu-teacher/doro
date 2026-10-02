package com.hunnit_beasts.auth.domain.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * @param idToken OIDC id_token(openid 스코프로 발급된 OAuth 교환에서만 존재, null 이면 응답에서 생략)
 * @param scope   OAuth 교환에서 승인된 스코프(없으면 응답에서 생략)
 */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UUID sessionId,
        int userIndex,
        @JsonInclude(JsonInclude.Include.NON_NULL) String idToken,
        @JsonInclude(JsonInclude.Include.NON_NULL) String scope
) {
    public static TokenResponse of(String accessToken, String refreshToken, long expiresIn, UUID sessionId, int userIndex) {
        return new TokenResponse(accessToken, refreshToken, "Bearer", expiresIn, sessionId, userIndex, null, null);
    }

    public static TokenResponse ofOAuth(String accessToken, String refreshToken, long expiresIn, UUID sessionId,
                                        int userIndex, String idToken, String scope) {
        return new TokenResponse(accessToken, refreshToken, "Bearer", expiresIn, sessionId, userIndex, idToken,
                scope == null || scope.isBlank() ? null : scope);
    }
}

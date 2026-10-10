package com.hunnit_beasts.auth.domain.oauth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hunnit_beasts.auth.domain.oauth.entity.OAuthClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 클라이언트 응답. clientSecret 은 등록·회전 직후 한 번만 채워지고 이후 조회에는 나오지 않는다(서버도 평문을 보관하지 않는다).
 */
public record OAuthClientResponse(
        UUID id,
        String clientId,
        String name,
        List<String> redirectUris,
        List<String> scopes,
        boolean active,
        boolean firstParty,
        boolean confidential,
        Instant createdAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) String clientSecret
) {
    public static OAuthClientResponse from(OAuthClient client) {
        return from(client, null);
    }

    public static OAuthClientResponse from(OAuthClient client, String clientSecret) {
        return new OAuthClientResponse(
                client.getId(),
                client.getClientId(),
                client.getName(),
                client.redirectUriList(),
                List.copyOf(client.allowedScopeSet()),
                client.isActive(),
                client.isFirstParty(),
                client.isConfidential(),
                client.getCreatedAt(),
                clientSecret);
    }
}

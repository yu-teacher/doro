package com.hunnit_beasts.auth.domain.oauth.dto;

import com.hunnit_beasts.auth.domain.oauth.entity.OAuthClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OAuthClientResponse(
        UUID id,
        String clientId,
        String name,
        List<String> redirectUris,
        List<String> scopes,
        boolean active,
        boolean firstParty,
        Instant createdAt
) {
    public static OAuthClientResponse from(OAuthClient client) {
        return new OAuthClientResponse(
                client.getId(),
                client.getClientId(),
                client.getName(),
                client.redirectUriList(),
                List.copyOf(client.allowedScopeSet()),
                client.isActive(),
                client.isFirstParty(),
                client.getCreatedAt());
    }
}

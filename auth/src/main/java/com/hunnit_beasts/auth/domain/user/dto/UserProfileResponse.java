package com.hunnit_beasts.auth.domain.user.dto;

import java.time.Instant;
import java.util.UUID;

public record UserProfileResponse(
        UUID id,
        String email,
        String name,
        String profileImageUrl,
        String status,
        String role,
        boolean hasTotp,
        Instant createdAt
) {
}

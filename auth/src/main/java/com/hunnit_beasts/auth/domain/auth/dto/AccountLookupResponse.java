package com.hunnit_beasts.auth.domain.auth.dto;

public record AccountLookupResponse(
        String email,
        String name,
        String profileImageUrl
) {}

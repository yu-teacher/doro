package com.hunnit_beasts.auth.domain.auth.dto;

public record TotpSetupResponse(
        String secret,
        String qrUri
) {
}

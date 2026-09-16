package com.hunnit_beasts.auth.domain.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginResponse(
        boolean requires2fa,
        String tempTicket,
        TokenResponse tokens
) {
    public static LoginResponse directSuccess(TokenResponse tokens) {
        return new LoginResponse(false, null, tokens);
    }

    public static LoginResponse requiresTwoFactor(String tempTicket) {
        return new LoginResponse(true, tempTicket, null);
    }
}

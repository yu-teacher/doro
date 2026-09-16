package com.hunnit_beasts.auth.domain.oauth.dto;

import jakarta.validation.constraints.NotBlank;

public record OAuth2TokenRequest(
        @NotBlank(message = "grant_type은 필수입니다.")
        String grantType,

        @NotBlank(message = "code는 필수입니다.")
        String code,

        @NotBlank(message = "redirect_uri는 필수입니다.")
        String redirectUri,

        @NotBlank(message = "client_id는 필수입니다.")
        String clientId,

        @NotBlank(message = "code_verifier는 필수입니다 (PKCE).")
        String codeVerifier
) {
}

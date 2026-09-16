package com.hunnit_beasts.auth.domain.oauth.dto;

import jakarta.validation.constraints.NotBlank;

public record OAuth2AuthorizeRequest(
        @NotBlank(message = "client_id는 필수입니다.")
        String clientId,

        @NotBlank(message = "redirect_uri는 필수입니다.")
        String redirectUri,

        @NotBlank(message = "response_type은 필수입니다.")
        String responseType,

        String scope,
        String state,

        @NotBlank(message = "code_challenge는 필수입니다 (PKCE).")
        String codeChallenge,

        String codeChallengeMethod
) {
}

package com.hunnit_beasts.auth.domain.oauth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** OAuth 클라이언트 등록 요청. scopes/clientId 는 생략 가능(기본 스코프, 서버 생성 client_id). */
public record OAuthClientCreateRequest(
        @NotBlank(message = "name 은 필수입니다.")
        @Size(max = 100, message = "name 은 최대 100자입니다.")
        String name,

        @NotNull(message = "redirectUris 는 필수입니다.")
        List<String> redirectUris,

        List<String> scopes,

        String clientId
) {
}

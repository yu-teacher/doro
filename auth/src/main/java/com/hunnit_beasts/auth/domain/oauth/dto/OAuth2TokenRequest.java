package com.hunnit_beasts.auth.domain.oauth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 토큰 요청(JSON camelCase / 폼 snake_case 공통 모델). grant_type 별 필수 필드는 서비스가
 * invalid_request 로 검증한다. (authorization_code: code, redirectUri, clientId, codeVerifier / refresh_token: refreshToken, clientId)
 */
public record OAuth2TokenRequest(
        @NotBlank(message = "grant_type은 필수입니다.")
        String grantType,
        String code,
        String redirectUri,
        String clientId,
        String codeVerifier,
        String refreshToken,
        String scope
) {
    /** authorization_code 교환용(기존 호출부 호환) */
    public OAuth2TokenRequest(String grantType, String code, String redirectUri, String clientId, String codeVerifier) {
        this(grantType, code, redirectUri, clientId, codeVerifier, null, null);
    }

    @Override
    public String toString() {
        // 코드·검증자·리프레시 토큰이 로그에 남지 않도록 값은 출력하지 않는다.
        return "OAuth2TokenRequest[grantType=" + grantType + ", clientId=" + clientId + "]";
    }
}

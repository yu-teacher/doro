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

        String clientId,

        /** 자사 서비스 표시(동의 화면 생략). 생략하면 false. */
        Boolean firstParty,

        /** true 이면 기밀 클라이언트로 등록하고 client_secret 을 한 번만 돌려준다. 생략하면 공개 클라이언트. */
        Boolean confidential
) {
    /** firstParty 를 생략하는 기존 호출을 위한 생성자. */
    public OAuthClientCreateRequest(String name, List<String> redirectUris, List<String> scopes, String clientId) {
        this(name, redirectUris, scopes, clientId, null, null);
    }

    /** confidential 을 생략하는 기존 호출을 위한 생성자. */
    public OAuthClientCreateRequest(String name, List<String> redirectUris, List<String> scopes, String clientId, Boolean firstParty) {
        this(name, redirectUris, scopes, clientId, firstParty, null);
    }
}

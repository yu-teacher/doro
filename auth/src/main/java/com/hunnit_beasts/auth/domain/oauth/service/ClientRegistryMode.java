package com.hunnit_beasts.auth.domain.oauth.service;

import java.util.Locale;

/** 클라이언트 레지스트리 적용 수준 ({@code doro.oauth.client-registry-mode}). */
public enum ClientRegistryMode {
    /** 레지스트리를 쓰지 않고 환경변수 redirect_uri 허용 목록만 사용 */
    OFF,
    /** 등록된 클라이언트는 레지스트리 규칙을 따르고, 미등록 client_id 는 환경변수 허용 목록으로 폴백(경고 로그) */
    WARN,
    /** 미등록 client_id 는 invalid_client 로 거부 */
    ENFORCE;

    public static ClientRegistryMode parse(String value) {
        if (value == null || value.isBlank()) {
            return WARN;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unsupported doro.oauth.client-registry-mode (expected OFF|WARN|ENFORCE)", e);
        }
    }
}

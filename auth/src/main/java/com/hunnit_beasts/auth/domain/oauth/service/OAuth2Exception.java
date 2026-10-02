package com.hunnit_beasts.auth.domain.oauth.service;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import lombok.Getter;

/**
 * OAuth 프로토콜 오류. {@link AuthException} 을 상속하므로 JSON 계약(기존 클라이언트)에서는 GlobalExceptionHandler 가 처리하고,
 * RFC 6749 형식(폼 토큰 요청, 브라우저 리다이렉트 오류)에서는 컨트롤러가 {@link #getType()} 과 설명을 그대로 사용한다.
 * 설명(message)은 짧아야 하며 코드·토큰·사용자 입력 원문을 포함하지 않는다.
 */
@Getter
public class OAuth2Exception extends AuthException {

    private final OAuth2ErrorType type;

    public OAuth2Exception(OAuth2ErrorType type, String description) {
        super(type.getJsonErrorCode(), description);
        this.type = type;
    }

    /** 기존 JSON 계약의 ErrorCode 를 유지해야 할 때(예: 리프레시 토큰 재사용 탐지) */
    public OAuth2Exception(OAuth2ErrorType type, String description, ErrorCode jsonErrorCode) {
        super(jsonErrorCode, description);
        this.type = type;
    }
}

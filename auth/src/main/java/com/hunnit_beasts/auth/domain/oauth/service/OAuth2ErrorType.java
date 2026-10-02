package com.hunnit_beasts.auth.domain.oauth.service;

import com.hunnit_beasts.auth.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * RFC 6749 의 error 코드와 HTTP 상태(토큰 엔드포인트 폼 응답용), 그리고 JSON(ApiResponse/ErrorResponse) 계약에서 쓰는
 * 기존 {@link ErrorCode} 의 매핑. JSON 계약에서는 클라이언트 오류도 포털이 "세션 만료(401)"로 오해하지 않도록 400 으로 둔다.
 */
@Getter
@RequiredArgsConstructor
public enum OAuth2ErrorType {
    INVALID_REQUEST("invalid_request", 400, ErrorCode.INVALID_INPUT),
    INVALID_CLIENT("invalid_client", 401, ErrorCode.INVALID_INPUT),
    INVALID_GRANT("invalid_grant", 400, ErrorCode.INVALID_TOKEN),
    UNSUPPORTED_GRANT_TYPE("unsupported_grant_type", 400, ErrorCode.INVALID_INPUT),
    UNSUPPORTED_RESPONSE_TYPE("unsupported_response_type", 400, ErrorCode.INVALID_INPUT),
    INVALID_SCOPE("invalid_scope", 400, ErrorCode.INVALID_INPUT);

    private final String code;
    private final int httpStatus;
    private final ErrorCode jsonErrorCode;
}

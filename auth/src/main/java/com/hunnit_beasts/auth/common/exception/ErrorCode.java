package com.hunnit_beasts.auth.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    // 400 Bad Request
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "AUTH_40001", "잘못된 입력값입니다."),
    INVALID_TOKEN(HttpStatus.BAD_REQUEST, "AUTH_40002", "유효하지 않은 토큰입니다."),
    TOKEN_REUSE_DETECTED(HttpStatus.BAD_REQUEST, "AUTH_40003", "토큰 재사용 공격이 감지되어 세션이 무효화되었습니다."),

    // 401 Unauthorized
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "AUTH_40101", "인증 정보가 유효하지 않습니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH_40102", "이메일 또는 비밀번호가 올바르지 않습니다."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "AUTH_40103", "토큰이 만료되었습니다."),
    SESSION_EXPIRED(HttpStatus.UNAUTHORIZED, "AUTH_40104", "세션이 만료되었습니다."),
    INVALID_2FA_CODE(HttpStatus.UNAUTHORIZED, "AUTH_40105", "2차 인증(OTP) 코드가 올바르지 않습니다."),

    // 403 Forbidden
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "AUTH_40300", "해당 작업을 수행할 권한이 없습니다."),
    ACCOUNT_LOCKED(HttpStatus.FORBIDDEN, "AUTH_40301", "비밀번호 연속 실패로 계정이 잠겼습니다. 잠시 후 다시 시도해 주세요."),
    ACCOUNT_SUSPENDED(HttpStatus.FORBIDDEN, "AUTH_40302", "이용 정지된 계정입니다."),

    // 404 Not Found
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "AUTH_40401", "사용자를 찾을 수 없습니다."),
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "AUTH_40402", "세션을 찾을 수 없습니다."),

    OAUTH_CLIENT_NOT_FOUND(HttpStatus.NOT_FOUND, "AUTH_40403", "OAuth 클라이언트를 찾을 수 없습니다."),

    // 429 Too Many Requests
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "AUTH_42901", "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),

    // 409 Conflict
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "AUTH_40901", "이미 사용 중인 이메일입니다."),
    ACCOUNT_STATE_CONFLICT(HttpStatus.CONFLICT, "AUTH_40903", "계정의 현재 상태에서는 처리할 수 없습니다."),
    OAUTH_CLIENT_ALREADY_EXISTS(HttpStatus.CONFLICT, "AUTH_40902", "이미 등록된 client_id 입니다."),

    // 503 Service Unavailable
    GUARD_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_50301", "권한 서버(Guard)에 연결할 수 없어 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."),

    // 500 Internal Server Error
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "AUTH_50001", "서버 내부 오류가 발생했습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}

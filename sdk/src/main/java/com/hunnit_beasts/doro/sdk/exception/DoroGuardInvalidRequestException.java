package com.hunnit_beasts.doro.sdk.exception;

/**
 * Guard 가 요청 자체를 잘못된 입력(INVALID_ARGUMENT)으로 거부한 경우. Guard 장애가 아니므로 DoroGuardUnavailableException 과 무관하다.
 * 호출한 서비스 코드의 버그(빈 ID, 잘못된 relation 등)를 뜻하며, 기본 예외 핸들러는 GUARD_INVALID_REQUEST(500) 로 응답한다.
 */
public class DoroGuardInvalidRequestException extends RuntimeException {

    public DoroGuardInvalidRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}

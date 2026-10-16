package com.hunnit_beasts.doro.sdk.exception;

/**
 * Guard 가 서비스 토큰을 거부한 경우(UNAUTHENTICATED / PERMISSION_DENIED). 장애가 아니라 운영자가 고쳐야 할 설정 오류다.
 * 기존 처리(503)를 유지하는 서비스와 호환되도록 DoroGuardUnavailableException 을 상속하며,
 * 기본 예외 핸들러는 이를 GUARD_MISCONFIGURED(500) 로 구분해 응답한다.
 */
public class DoroGuardMisconfiguredException extends DoroGuardUnavailableException {

    public DoroGuardMisconfiguredException(String message, Throwable cause) {
        super(message, cause);
    }
}

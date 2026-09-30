package com.hunnit_beasts.doro.sdk.exception;

/**
 * Guard 서버에 도달하지 못했거나 응답을 받지 못해 인가 여부를 판단할 수 없는 경우.
 * 기존 처리(403) 를 유지하는 서비스와 호환되도록 DoroAccessDeniedException 을 상속한다.
 * 기본 예외 핸들러는 이를 503 으로 구분해 응답한다.
 */
public class DoroGuardUnavailableException extends DoroAccessDeniedException {

    public DoroGuardUnavailableException(String message, Throwable cause) {
        super(message);
        initCause(cause);
    }
}

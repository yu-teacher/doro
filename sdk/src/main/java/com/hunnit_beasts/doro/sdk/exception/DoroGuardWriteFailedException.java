package com.hunnit_beasts.doro.sdk.exception;

/** Guard 에 관계 튜플을 쓰거나 지우지 못한 경우. 서비스 DB 와 Guard 상태가 어긋날 수 있으므로 호출자가 처리해야 한다. */
public class DoroGuardWriteFailedException extends RuntimeException {

    public DoroGuardWriteFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}

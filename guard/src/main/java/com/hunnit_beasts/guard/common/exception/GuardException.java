package com.hunnit_beasts.guard.common.exception;

import lombok.Getter;

@Getter
public class GuardException extends RuntimeException {

    private final ErrorCode errorCode;

    public GuardException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public GuardException(ErrorCode errorCode, String customMessage) {
        super(customMessage);
        this.errorCode = errorCode;
    }
}

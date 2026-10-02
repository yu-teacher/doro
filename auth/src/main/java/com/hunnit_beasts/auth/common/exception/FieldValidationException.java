package com.hunnit_beasts.auth.common.exception;

import lombok.Getter;

/**
 * Bean Validation 어노테이션으로 표현할 수 없는(설정값 기반 등) 필드 검증 실패.
 * 어노테이션 검증 실패와 동일한 400 INVALID_INPUT_VALUE 응답 규격으로 변환된다.
 */
@Getter
public class FieldValidationException extends RuntimeException {

    private final String field;
    private final String reason;

    public FieldValidationException(String field, String reason) {
        super(reason);
        this.field = field;
        this.reason = reason;
    }
}

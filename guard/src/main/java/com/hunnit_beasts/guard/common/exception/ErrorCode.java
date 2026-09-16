package com.hunnit_beasts.guard.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "GUARD_40001", "잘못된 입력값입니다."),
    INVALID_SYNTAX(HttpStatus.BAD_REQUEST, "GUARD_40002", "스키마 DSL 문법 오류입니다."),
    SCHEMA_NOT_FOUND(HttpStatus.NOT_FOUND, "GUARD_40401", "스키마 정의를 찾을 수 없습니다."),
    TUPLE_NOT_FOUND(HttpStatus.NOT_FOUND, "GUARD_40402", "관계 튜플을 찾을 수 없습니다."),
    CYCLE_DETECTED(HttpStatus.BAD_REQUEST, "GUARD_40003", "순환 참조가 감지되었습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "GUARD_50001", "서버 내부 오류가 발생했습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}

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
    INVALID_TUPLE(HttpStatus.BAD_REQUEST, "GUARD_40004", "스키마에 선언되지 않은 타입/릴레이션을 사용하는 튜플입니다."),
    CYCLE_DETECTED(HttpStatus.BAD_REQUEST, "GUARD_40003", "순환 참조가 감지되었습니다."),
    SCHEMA_CONFLICT(HttpStatus.CONFLICT, "GUARD_40901", "스키마 버전 충돌이 반복되어 등록하지 못했습니다. 잠시 후 다시 시도하세요."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "GUARD_50001", "서버 내부 오류가 발생했습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}

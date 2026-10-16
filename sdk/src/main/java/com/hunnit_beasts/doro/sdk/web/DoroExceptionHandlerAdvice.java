package com.hunnit_beasts.doro.sdk.web;

import com.hunnit_beasts.doro.sdk.domain.DoroUserContext;
import com.hunnit_beasts.doro.sdk.exception.DoroAccessDeniedException;
import com.hunnit_beasts.doro.sdk.exception.DoroGuardInvalidRequestException;
import com.hunnit_beasts.doro.sdk.exception.DoroGuardMisconfiguredException;
import com.hunnit_beasts.doro.sdk.exception.DoroGuardUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DoroAccessDeniedException 의 기본 HTTP 변환. 서비스가 같은 예외에 대한 자체 핸들러를 가지고 있으면 그쪽이 우선한다.
 * 비로그인 401 / 권한 없음 403 / Guard 장애 503 으로 구분하며, 사용자 ID·객체 ID 는 응답에 싣지 않는다.
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class DoroExceptionHandlerAdvice {

    @ExceptionHandler(DoroGuardUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleUnavailable(DoroGuardUnavailableException e) {
        log.error("Doro Guard unavailable: {}", e.getMessage());
        return body(HttpStatus.SERVICE_UNAVAILABLE, "GUARD_UNAVAILABLE", "인가 서버를 일시적으로 사용할 수 없습니다.");
    }

    /** 서비스 토큰 오설정: 장애가 아니므로 503 이 아닌 500 과 별도 코드로 알린다(운영자가 설정을 고쳐야 함). */
    @ExceptionHandler(DoroGuardMisconfiguredException.class)
    public ResponseEntity<Map<String, Object>> handleMisconfigured(DoroGuardMisconfiguredException e) {
        log.error("Doro Guard service token rejected (configuration error): {}", e.getMessage());
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "GUARD_MISCONFIGURED", "인가 서버 연동 설정에 문제가 있습니다.");
    }

    /** Guard 가 입력 오류로 거부한 경우: 장애(503)가 아니라 호출 코드 버그다. */
    @ExceptionHandler(DoroGuardInvalidRequestException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidRequest(DoroGuardInvalidRequestException e) {
        log.error("Doro Guard rejected request as invalid: {}", e.getMessage());
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "GUARD_INVALID_REQUEST", "인가 요청이 올바르지 않습니다.");
    }

    @ExceptionHandler(DoroAccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleDenied(DoroAccessDeniedException e) {
        if (!DoroUserContext.getCurrentUser().isAuthenticated()) {
            return body(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다.");
        }
        log.warn("Doro access denied: namespace={}, relation={}", e.getNamespace(), e.getRelation());
        return body(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "해당 리소스에 대한 권한이 없습니다.");
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code);
        body.put("message", message);
        body.put("status", status.value());
        return ResponseEntity.status(status).body(body);
    }
}

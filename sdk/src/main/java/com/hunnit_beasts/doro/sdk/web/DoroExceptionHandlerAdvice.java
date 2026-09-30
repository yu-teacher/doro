package com.hunnit_beasts.doro.sdk.web;

import com.hunnit_beasts.doro.sdk.domain.DoroUserContext;
import com.hunnit_beasts.doro.sdk.exception.DoroAccessDeniedException;
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

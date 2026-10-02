package com.hunnit_beasts.guard.common;

import com.hunnit_beasts.guard.common.exception.ErrorCode;
import com.hunnit_beasts.guard.common.exception.GlobalExceptionHandler;
import com.hunnit_beasts.guard.common.exception.GuardException;
import com.hunnit_beasts.guard.common.response.ErrorResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerConflictTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("SCHEMA_CONFLICT 는 409, INVALID_TUPLE 은 400 으로 표준 에러 규격에 담겨 반환된다")
    void newErrorCodesAreMapped() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/guard/schema");

        ResponseEntity<ErrorResponse> conflict = handler.handleGuardException(new GuardException(ErrorCode.SCHEMA_CONFLICT), request);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(conflict.getBody().getCode()).isEqualTo("SCHEMA_CONFLICT");

        ResponseEntity<ErrorResponse> tuple = handler.handleGuardException(new GuardException(ErrorCode.INVALID_TUPLE, "x"), request);
        assertThat(tuple.getStatusCode().value()).isEqualTo(400);
        assertThat(tuple.getBody().getCode()).isEqualTo("INVALID_TUPLE");
    }
}

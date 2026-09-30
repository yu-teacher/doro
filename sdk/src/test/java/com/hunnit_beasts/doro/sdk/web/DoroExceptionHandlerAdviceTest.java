package com.hunnit_beasts.doro.sdk.web;

import com.hunnit_beasts.doro.sdk.client.DoroGuardClient;
import com.hunnit_beasts.doro.sdk.domain.DoroUser;
import com.hunnit_beasts.doro.sdk.domain.DoroUserContext;
import com.hunnit_beasts.doro.sdk.exception.DoroAccessDeniedException;
import com.hunnit_beasts.doro.sdk.exception.DoroGuardUnavailableException;
import com.hunnit_beasts.doro.sdk.exception.DoroGuardWriteFailedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DoroExceptionHandlerAdviceTest {

    private final DoroExceptionHandlerAdvice advice = new DoroExceptionHandlerAdvice();

    @AfterEach
    void tearDown() {
        DoroUserContext.clear();
    }

    @Test
    @DisplayName("비로그인 상태의 접근 거부는 401")
    void unauthenticatedIs401() {
        var response = advice.handleDenied(new DoroAccessDeniedException("인증되지 않은 사용자입니다."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("로그인 상태의 접근 거부는 403 이고 응답에 사용자/객체 ID 가 없다")
    void authenticatedDenialIs403WithoutIdentifiers() {
        UUID userId = UUID.randomUUID();
        DoroUserContext.setCurrentUser(new DoroUser(userId, "u@doro.local", UUID.randomUUID(), 0));

        var response = advice.handleDenied(new DoroAccessDeniedException("board_post", "secret-object-42", "viewer", userId.toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().toString()).doesNotContain(userId.toString()).doesNotContain("secret-object-42");
    }

    @Test
    @DisplayName("Guard 장애는 503")
    void guardOutageIs503() {
        var response = advice.handleUnavailable(new DoroGuardUnavailableException("down", new RuntimeException()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("OrThrow 변형: Guard 에 접속할 수 없으면 거부(false)가 아니라 예외로 구분된다")
    void orThrowVariantsSurfaceOutage() {
        DoroGuardClient unreachable = new DoroGuardClient("localhost", 59998, 1);
        try {
            assertThat(unreachable.check("doc", "1", "viewer", "u1")).isFalse();
            assertThatThrownBy(() -> unreachable.checkOrThrow("doc", "1", "viewer", "u1"))
                    .isInstanceOf(DoroGuardUnavailableException.class)
                    .isInstanceOf(DoroAccessDeniedException.class);
            assertThatThrownBy(() -> unreachable.writeTupleOrThrow("doc", "1", "viewer", "user", "u1", null))
                    .isInstanceOf(DoroGuardWriteFailedException.class);
            assertThatThrownBy(() -> unreachable.deleteTupleOrThrow("doc", "1", "viewer", "user", "u1", null))
                    .isInstanceOf(DoroGuardWriteFailedException.class);
        } finally {
            unreachable.shutdown();
        }
    }
}

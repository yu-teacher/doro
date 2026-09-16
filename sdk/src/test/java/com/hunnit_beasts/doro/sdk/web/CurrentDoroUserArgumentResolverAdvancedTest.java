package com.hunnit_beasts.doro.sdk.web;

import com.hunnit_beasts.doro.sdk.annotation.CurrentDoroUser;
import com.hunnit_beasts.doro.sdk.domain.DoroUser;
import com.hunnit_beasts.doro.sdk.domain.DoroUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentDoroUserArgumentResolverAdvancedTest {

    private final CurrentDoroUserArgumentResolver resolver = new CurrentDoroUserArgumentResolver();
    private UUID testUserId;

    static class ControllerSample {
        public void handleUser(@CurrentDoroUser DoroUser user) {}
        public void handleUuid(@CurrentDoroUser UUID userId) {}
        public void handleString(@CurrentDoroUser String userIdStr) {}
    }

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID();
        DoroUserContext.setCurrentUser(new DoroUser(testUserId, "resolver@doro.local", UUID.randomUUID(), 2));
    }

    @AfterEach
    void tearDown() {
        DoroUserContext.clear();
    }

    @Test
    @DisplayName("파라미터 주입: @CurrentDoroUser DoroUser 객체 정상 주입")
    void testResolveDoroUserObject() throws Exception {
        Method method = ControllerSample.class.getMethod("handleUser", DoroUser.class);
        MethodParameter param = new MethodParameter(method, 0);

        assertThat(resolver.supportsParameter(param)).isTrue();
        Object resolved = resolver.resolveArgument(param, null, null, null);

        assertThat(resolved).isInstanceOf(DoroUser.class);
        DoroUser user = (DoroUser) resolved;
        assertThat(user.userId()).isEqualTo(testUserId);
        assertThat(user.userIndex()).isEqualTo(2);
    }

    @Test
    @DisplayName("파라미터 주입: @CurrentDoroUser UUID (유저 ID) 정상 주입")
    void testResolveUuid() throws Exception {
        Method method = ControllerSample.class.getMethod("handleUuid", UUID.class);
        MethodParameter param = new MethodParameter(method, 0);

        assertThat(resolver.supportsParameter(param)).isTrue();
        Object resolved = resolver.resolveArgument(param, null, null, null);

        assertThat(resolved).isEqualTo(testUserId);
    }

    @Test
    @DisplayName("파라미터 주입: @CurrentDoroUser String (유저 ID 문자열) 정상 주입")
    void testResolveString() throws Exception {
        Method method = ControllerSample.class.getMethod("handleString", String.class);
        MethodParameter param = new MethodParameter(method, 0);

        assertThat(resolver.supportsParameter(param)).isTrue();
        Object resolved = resolver.resolveArgument(param, null, null, null);

        assertThat(resolved).isEqualTo(testUserId.toString());
    }

    @Test
    @DisplayName("파라미터 주입 엣지케이스: 미인증 요청 시 UUID 및 String 파라미터는 null 반환")
    void testResolveUnauthenticatedReturnsNull() throws Exception {
        DoroUserContext.clear();

        Method uuidMethod = ControllerSample.class.getMethod("handleUuid", UUID.class);
        MethodParameter uuidParam = new MethodParameter(uuidMethod, 0);
        assertThat(resolver.resolveArgument(uuidParam, null, null, null)).isNull();

        Method strMethod = ControllerSample.class.getMethod("handleString", String.class);
        MethodParameter strParam = new MethodParameter(strMethod, 0);
        assertThat(resolver.resolveArgument(strParam, null, null, null)).isNull();
    }
}

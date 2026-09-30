package com.hunnit_beasts.doro.sdk.aop;

import com.hunnit_beasts.doro.sdk.annotation.DoroGuard;
import com.hunnit_beasts.doro.sdk.client.DoroGuardClient;
import com.hunnit_beasts.doro.sdk.domain.DoroUser;
import com.hunnit_beasts.doro.sdk.domain.DoroUserContext;
import com.hunnit_beasts.doro.sdk.exception.DoroAccessDeniedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DoroGuardClassLevelAndDisabledTest {

    @DoroGuard("board_post:#id#viewer")
    static class ClassGuardedService {
        public String read(String id) {
            return "read " + id;
        }

        @DoroGuard("board_post:#id#editor")
        public String write(String id) {
            return "write " + id;
        }
    }

    @AfterEach
    void tearDown() {
        DoroUserContext.clear();
    }

    private ClassGuardedService proxy(DoroGuardClient client) {
        AspectJProxyFactory factory = new AspectJProxyFactory(new ClassGuardedService());
        factory.addAspect(new DoroGuardAspect(client));
        return factory.getProxy();
    }

    private void loginAs(UUID userId) {
        DoroUserContext.setCurrentUser(new DoroUser(userId, "u@doro.local", UUID.randomUUID(), 0));
    }

    @Test
    @DisplayName("클래스 레벨 @DoroGuard: 권한이 없으면 메서드 실행 전에 거부")
    void classLevelGuardDenies() {
        DoroGuardClient client = Mockito.mock(DoroGuardClient.class);
        UUID userId = UUID.randomUUID();
        loginAs(userId);
        when(client.check("board_post", "p1", "viewer", userId.toString())).thenReturn(false);

        assertThatThrownBy(() -> proxy(client).read("p1")).isInstanceOf(DoroAccessDeniedException.class);
    }

    @Test
    @DisplayName("클래스 레벨 @DoroGuard: 권한이 있으면 통과")
    void classLevelGuardAllows() {
        DoroGuardClient client = Mockito.mock(DoroGuardClient.class);
        UUID userId = UUID.randomUUID();
        loginAs(userId);
        when(client.check("board_post", "p1", "viewer", userId.toString())).thenReturn(true);

        assertThat(proxy(client).read("p1")).isEqualTo("read p1");
    }

    @Test
    @DisplayName("메서드 레벨 @DoroGuard 가 있으면 클래스 레벨보다 우선하고 한 번만 검사")
    void methodLevelWinsOverClassLevel() {
        DoroGuardClient client = Mockito.mock(DoroGuardClient.class);
        UUID userId = UUID.randomUUID();
        loginAs(userId);
        when(client.check("board_post", "p1", "editor", userId.toString())).thenReturn(true);

        assertThat(proxy(client).write("p1")).isEqualTo("write p1");
        verify(client, never()).check("board_post", "p1", "viewer", userId.toString());
    }

    @Test
    @DisplayName("Guard 비활성(client 없음): 보호된 메서드는 실행되지 않고 fail-closed")
    void disabledGuardFailsClosed() {
        loginAs(UUID.randomUUID());

        assertThatThrownBy(() -> proxy(null).read("p1")).isInstanceOf(DoroAccessDeniedException.class);
    }
}

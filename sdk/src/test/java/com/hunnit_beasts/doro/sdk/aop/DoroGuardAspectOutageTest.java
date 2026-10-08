package com.hunnit_beasts.doro.sdk.aop;

import com.hunnit_beasts.doro.sdk.annotation.DoroGuard;
import com.hunnit_beasts.doro.sdk.client.DoroGuardClient;
import com.hunnit_beasts.doro.sdk.domain.DoroUser;
import com.hunnit_beasts.doro.sdk.domain.DoroUserContext;
import com.hunnit_beasts.doro.sdk.exception.DoroAccessDeniedException;
import com.hunnit_beasts.doro.sdk.exception.DoroGuardUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * @DoroGuard 에서 Guard 장애와 실제 거부를 구분한다. 장애가 거부(403)로 둔갑하면 서비스 운영자가
 * 권한 문제와 인프라 장애를 구분하지 못한다. (장애 → DoroGuardUnavailableException → 503)
 */
class DoroGuardAspectOutageTest {

    private DoroGuardClient guardClient;
    private SampleService service;
    private UUID userId;

    static class SampleService {
        @DoroGuard(namespace = "document", object = "#docId", relation = "viewer")
        public String read(String docId) {
            return "ok " + docId;
        }
    }

    @BeforeEach
    void setUp() {
        guardClient = Mockito.mock(DoroGuardClient.class);
        AspectJProxyFactory factory = new AspectJProxyFactory(new SampleService());
        factory.addAspect(new DoroGuardAspect(guardClient));
        service = factory.getProxy();
        userId = UUID.randomUUID();
        DoroUserContext.setCurrentUser(new DoroUser(userId, "t@doro.local", UUID.randomUUID(), 0));
    }

    @AfterEach
    void tearDown() {
        DoroUserContext.clear();
    }

    @Test
    @DisplayName("Guard 장애는 거부가 아니라 DoroGuardUnavailableException 으로 전달된다")
    void outageIsNotReportedAsDenial() {
        when(guardClient.checkOrThrow(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new DoroGuardUnavailableException("down", new RuntimeException("UNAVAILABLE")));

        assertThatThrownBy(() -> service.read("d1")).isInstanceOf(DoroGuardUnavailableException.class);
    }

    @Test
    @DisplayName("Guard 가 정상 응답으로 거부하면 여전히 일반 거부(장애 아님)다")
    void genuineDenialIsStillADenial() {
        when(guardClient.checkOrThrow(anyString(), anyString(), anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.read("d1"))
                .isInstanceOf(DoroAccessDeniedException.class)
                .isNotInstanceOf(DoroGuardUnavailableException.class);
    }

    @Test
    @DisplayName("허용되면 본문이 실행된다")
    void allowedProceeds() {
        when(guardClient.checkOrThrow("document", "d1", "viewer", userId.toString())).thenReturn(true);

        assertThat(service.read("d1")).isEqualTo("ok d1");
    }
}

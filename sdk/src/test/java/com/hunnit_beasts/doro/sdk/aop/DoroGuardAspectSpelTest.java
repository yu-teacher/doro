package com.hunnit_beasts.doro.sdk.aop;

import com.hunnit_beasts.doro.sdk.annotation.DoroGuard;
import com.hunnit_beasts.doro.sdk.client.DoroGuardClient;
import com.hunnit_beasts.doro.sdk.domain.DoroUser;
import com.hunnit_beasts.doro.sdk.domain.DoroUserContext;
import com.hunnit_beasts.doro.sdk.exception.DoroAccessDeniedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class DoroGuardAspectSpelTest {

    private DoroGuardClient mockGuardClient;
    private SampleService proxyService;
    private UUID testUserId;

    static class SampleService {
        @DoroGuard(namespace = "document", object = "#docId", relation = "viewer")
        public String getDocument(String docId) {
            return "Content of " + docId;
        }

        @DoroGuard("document:#docId#editor")
        public String updateDocument(String docId, String title) {
            return "Updated " + docId + " with " + title;
        }
    }

    @BeforeEach
    void setUp() {
        mockGuardClient = Mockito.mock(DoroGuardClient.class);
        DoroGuardAspect aspect = new DoroGuardAspect(mockGuardClient);

        SampleService target = new SampleService();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        proxyService = factory.getProxy();

        testUserId = UUID.randomUUID();
        DoroUserContext.setCurrentUser(new DoroUser(testUserId, "test@doro.local", UUID.randomUUID(), 0));
    }

    @AfterEach
    void tearDown() {
        DoroUserContext.clear();
    }

    @Test
    @DisplayName("SpEL 파라미터 바인딩: @DoroGuard(namespace, object='#docId', relation='viewer') 정상 승인")
    void testSpelParameterBindingSuccess() {
        when(mockGuardClient.check(eq("document"), eq("doc-100"), eq("viewer"), eq(testUserId.toString())))
                .thenReturn(true);

        String result = proxyService.getDocument("doc-100");
        assertThat(result).isEqualTo("Content of doc-100");
    }

    @Test
    @DisplayName("단축형 SpEL 표현식: @DoroGuard('document:#docId#editor') 권한 부족 시 DoroAccessDeniedException 발생")
    void testShorthandSpelAccessDenied() {
        when(mockGuardClient.check(eq("document"), eq("doc-200"), eq("editor"), eq(testUserId.toString())))
                .thenReturn(false);

        assertThatThrownBy(() -> proxyService.updateDocument("doc-200", "New Title"))
                .isInstanceOf(DoroAccessDeniedException.class)
                .hasMessageContaining("doc-200");
    }
}

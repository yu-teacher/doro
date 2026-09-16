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

class DoroGuardSpelAdvancedEdgeCaseTest {

    private DoroGuardClient mockGuardClient;
    private AdvancedSampleService proxyService;
    private UUID currentUserId;

    public record MetaDto(String docId, String title) {}
    public record DocumentRequest(MetaDto meta, String content) {}

    static class AdvancedSampleService {
        // 1. 중첩 DTO 프로퍼티 SpEL
        @DoroGuard(namespace = "document", object = "#request.meta.docId", relation = "editor")
        public String updateWithNestedDto(DocumentRequest request) {
            return "Updated " + request.meta().docId();
        }

        // 2. Custom Subject 지정 (대리 권한 검사)
        @DoroGuard(namespace = "document", object = "#docId", relation = "viewer", subject = "#targetUserId")
        public boolean checkOtherUserPermission(String docId, String targetUserId) {
            return true;
        }

        // 3. Null 파라미터가 들어올 수 있는 메서드
        @DoroGuard(namespace = "document", object = "#docId", relation = "viewer")
        public String getNullable(String docId) {
            return "OK";
        }
    }

    @BeforeEach
    void setUp() {
        mockGuardClient = Mockito.mock(DoroGuardClient.class);
        DoroGuardAspect aspect = new DoroGuardAspect(mockGuardClient);

        AdvancedSampleService target = new AdvancedSampleService();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        proxyService = factory.getProxy();

        currentUserId = UUID.randomUUID();
        DoroUserContext.setCurrentUser(new DoroUser(currentUserId, "caller@doro.local", UUID.randomUUID(), 0));
    }

    @AfterEach
    void tearDown() {
        DoroUserContext.clear();
    }

    @Test
    @DisplayName("SpEL 심화: 중첩 DTO 프로퍼티 (#request.meta.docId) 정상 파싱 및 권한 검증")
    void testNestedDtoSpelResolution() {
        DocumentRequest req = new DocumentRequest(new MetaDto("doc-nested-999", "Architecture"), "Body content");

        when(mockGuardClient.check(eq("document"), eq("doc-nested-999"), eq("editor"), eq(currentUserId.toString())))
                .thenReturn(true);

        String result = proxyService.updateWithNestedDto(req);
        assertThat(result).isEqualTo("Updated doc-nested-999");
    }

    @Test
    @DisplayName("SpEL 심화: 대리 권한 검사 (subject = '#targetUserId') - 현재 로그인 유저가 아닌 대상 유저 권한 확인")
    void testCustomSubjectDelegatedCheck() {
        String targetUserId = UUID.randomUUID().toString();

        when(mockGuardClient.check(eq("document"), eq("doc-1"), eq("viewer"), eq(targetUserId)))
                .thenReturn(true);

        boolean allowed = proxyService.checkOtherUserPermission("doc-1", targetUserId);
        assertThat(allowed).isTrue();
    }

    @Test
    @DisplayName("엣지케이스: 인증되지 않은 익명 사용자가 @DoroGuard 메서드 호출 시 DoroAccessDeniedException 발생")
    void testUnauthenticatedUserThrowsException() {
        DoroUserContext.clear(); // 인증 정보 제거

        assertThatThrownBy(() -> proxyService.getNullable("doc-100"))
                .isInstanceOf(DoroAccessDeniedException.class)
                .hasMessageContaining("인증되지 않은 사용자");
    }

    @Test
    @DisplayName("엣지케이스: SpEL 대상 파라미터가 null 일 때 NPE 없이 안전하게 거절 처리")
    void testNullParameterHandledGracefully() {
        when(mockGuardClient.check(eq("document"), eq(""), eq("viewer"), eq(currentUserId.toString())))
                .thenReturn(false);

        assertThatThrownBy(() -> proxyService.getNullable(null))
                .isInstanceOf(DoroAccessDeniedException.class);
    }
}

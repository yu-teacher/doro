package com.hunnit_beasts.guard.config;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServiceTokenAuthTest {

    private static final String TOKEN = "unit-test-token";

    private ServiceAuthProperties properties(ServiceAuthProperties.Mode mode) {
        ServiceAuthProperties properties = new ServiceAuthProperties();
        properties.setMode(mode);
        properties.setServiceToken(TOKEN);
        return properties;
    }

    private int callRest(ServiceAuthProperties properties, String path, String token) throws Exception {
        ServiceTokenFilter filter = new ServiceTokenFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        if (token != null) {
            request.addHeader(ServiceAuthProperties.HEADER_NAME, token);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    @DisplayName("OFF: 토큰이 없어도 통과 (기존 동작 유지)")
    void offAllowsEverything() throws Exception {
        assertThat(callRest(properties(ServiceAuthProperties.Mode.OFF), "/api/v1/guard/check", null)).isEqualTo(200);
    }

    @Test
    @DisplayName("WARN: 토큰이 없어도 통과하되 기록만 한다")
    void warnAllowsButLogs() throws Exception {
        assertThat(callRest(properties(ServiceAuthProperties.Mode.WARN), "/api/v1/guard/check", null)).isEqualTo(200);
    }

    @Test
    @DisplayName("ENFORCE: 토큰이 없거나 틀리면 401, 맞으면 통과")
    void enforceRejectsMissingOrWrongToken() throws Exception {
        ServiceAuthProperties enforce = properties(ServiceAuthProperties.Mode.ENFORCE);
        assertThat(callRest(enforce, "/api/v1/guard/tuples", null)).isEqualTo(401);
        assertThat(callRest(enforce, "/api/v1/guard/tuples", "wrong")).isEqualTo(401);
        assertThat(callRest(enforce, "/api/v1/guard/tuples", TOKEN)).isEqualTo(200);
    }

    @Test
    @DisplayName("ENFORCE: 서비스 토큰이 설정되지 않았으면 어떤 값도 통과시키지 않는다")
    void enforceWithoutConfiguredTokenRejectsEverything() throws Exception {
        ServiceAuthProperties enforce = properties(ServiceAuthProperties.Mode.ENFORCE);
        enforce.setServiceToken("");
        assertThat(callRest(enforce, "/api/v1/guard/check", "")).isEqualTo(401);
    }

    @Test
    @DisplayName("ENFORCE 여도 /api/v1/guard 밖의 경로(health 등)는 검사하지 않는다")
    void nonGuardPathsAreNotProtected() throws Exception {
        assertThat(callRest(properties(ServiceAuthProperties.Mode.ENFORCE), "/actuator/health", null)).isEqualTo(200);
    }

    @SuppressWarnings("unchecked")
    private Status grpcCloseStatus(ServiceAuthProperties properties, String token) {
        ServiceTokenServerInterceptor interceptor = new ServiceTokenServerInterceptor(properties);
        ServerCall<Object, Object> call = mock(ServerCall.class);
        MethodDescriptor<Object, Object> descriptor = MethodDescriptor.<Object, Object>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName("doro.guard.v1.GuardService/Check")
                .setRequestMarshaller(mock(MethodDescriptor.Marshaller.class))
                .setResponseMarshaller(mock(MethodDescriptor.Marshaller.class))
                .build();
        when(call.getMethodDescriptor()).thenReturn(descriptor);
        ServerCallHandler<Object, Object> next = mock(ServerCallHandler.class);

        Metadata headers = new Metadata();
        if (token != null) {
            headers.put(Metadata.Key.of("x-doro-service-token", Metadata.ASCII_STRING_MARSHALLER), token);
        }
        interceptor.interceptCall(call, headers, next);

        ArgumentCaptor<Status> captor = ArgumentCaptor.forClass(Status.class);
        try {
            verify(call).close(captor.capture(), any(Metadata.class));
            verify(next, never()).startCall(any(), any());
            return captor.getValue();
        } catch (AssertionError notClosed) {
            return null;
        }
    }

    @Test
    @DisplayName("gRPC ENFORCE: 토큰이 없으면 UNAUTHENTICATED 로 닫고, 맞으면 통과")
    void grpcEnforce() {
        ServiceAuthProperties enforce = properties(ServiceAuthProperties.Mode.ENFORCE);
        Status missing = grpcCloseStatus(enforce, null);
        assertThat(missing).isNotNull();
        assertThat(missing.getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);

        assertThat(grpcCloseStatus(enforce, TOKEN)).isNull();
    }

    @Test
    @DisplayName("gRPC WARN/OFF: 토큰이 없어도 통과")
    void grpcWarnAndOffPass() {
        assertThat(grpcCloseStatus(properties(ServiceAuthProperties.Mode.WARN), null)).isNull();
        assertThat(grpcCloseStatus(properties(ServiceAuthProperties.Mode.OFF), null)).isNull();
    }

    private static final String AUTH_TOKEN = "auth-token-0123456789abcdef0123456789abcdef";
    private static final String BLOG_TOKEN = "blog-token-0123456789abcdef0123456789abcdef";

    private ServiceAuthProperties perCaller(String sharedToken) {
        ServiceAuthProperties properties = new ServiceAuthProperties();
        properties.setMode(ServiceAuthProperties.Mode.ENFORCE);
        properties.setServiceToken(sharedToken);
        properties.setServiceTokens("auth:" + AUTH_TOKEN + ", blog:" + BLOG_TOKEN);
        return properties;
    }

    @Test
    @DisplayName("호출자별 토큰: 각자의 토큰은 통과하고 호출자 이름으로 식별된다")
    void perCallerTokensIdentifyTheCaller() throws Exception {
        ServiceAuthProperties properties = perCaller("");
        assertThat(properties.authenticate(AUTH_TOKEN)).contains("auth");
        assertThat(properties.authenticate(BLOG_TOKEN)).contains("blog");
        assertThat(properties.authenticate("wrong")).isEmpty();
        assertThat(callRest(properties, "/api/v1/guard/check", AUTH_TOKEN)).isEqualTo(200);
        assertThat(callRest(properties, "/api/v1/guard/check", BLOG_TOKEN)).isEqualTo(200);
        assertThat(callRest(properties, "/api/v1/guard/check", "wrong")).isEqualTo(401);
        assertThat(callRest(properties, "/api/v1/guard/check", null)).isEqualTo(401);
    }

    @Test
    @DisplayName("전환 기간: 공유 토큰과 호출자별 토큰이 함께 통과하고, 공유 토큰을 지우면 공유 토큰은 거부된다")
    void sharedTokenWorksUntilRemoved() throws Exception {
        ServiceAuthProperties both = perCaller(TOKEN);
        assertThat(both.authenticate(TOKEN)).contains(ServiceAuthProperties.SHARED_CALLER);
        assertThat(callRest(both, "/api/v1/guard/check", TOKEN)).isEqualTo(200);
        assertThat(callRest(both, "/api/v1/guard/check", AUTH_TOKEN)).isEqualTo(200);

        ServiceAuthProperties finalized = perCaller("");
        assertThat(callRest(finalized, "/api/v1/guard/check", TOKEN)).isEqualTo(401);
        assertThat(callRest(finalized, "/api/v1/guard/check", AUTH_TOKEN)).isEqualTo(200);
    }

    @Test
    @DisplayName("공유 토큰은 호출자별 토큰이 설정된 뒤에만 정리 대상(deprecated)으로 표시된다")
    void sharedTokenIsDeprecatedOnlyOncePerCallerTokensExist() {
        ServiceAuthProperties onlyShared = properties(ServiceAuthProperties.Mode.ENFORCE);
        assertThat(onlyShared.isSharedTokenDeprecated(ServiceAuthProperties.SHARED_CALLER)).isFalse();

        ServiceAuthProperties both = perCaller(TOKEN);
        assertThat(both.isSharedTokenDeprecated(ServiceAuthProperties.SHARED_CALLER)).isTrue();
        assertThat(both.isSharedTokenDeprecated("auth")).isFalse();
    }

    @Test
    @DisplayName("gRPC ENFORCE: 호출자별 토큰도 통과한다")
    void grpcAcceptsPerCallerToken() {
        ServiceAuthProperties properties = perCaller("");
        assertThat(grpcCloseStatus(properties, BLOG_TOKEN)).isNull();
        assertThat(grpcCloseStatus(properties, "wrong")).isNotNull();
    }

    @Test
    @DisplayName("잘못된 service-tokens 설정은 기동 시점에 실패한다 (짧은 토큰, 중복 토큰, 잘못된 이름, 예약 이름)")
    void invalidSpecFailsFast() {
        ServiceAuthProperties properties = new ServiceAuthProperties();
        assertThatThrownBy(() -> properties.setServiceTokens("auth:short")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setServiceTokens("auth:" + AUTH_TOKEN + ",blog:" + AUTH_TOKEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setServiceTokens("Bad_Name:" + AUTH_TOKEN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setServiceTokens(AUTH_TOKEN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setServiceTokens("shared:" + AUTH_TOKEN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setServiceTokens("auth:" + AUTH_TOKEN + ",auth:" + BLOG_TOKEN))
                .isInstanceOf(IllegalArgumentException.class);
        properties.setServiceTokens("");
        assertThat(properties.getCallerTokens()).isEmpty();
    }
}

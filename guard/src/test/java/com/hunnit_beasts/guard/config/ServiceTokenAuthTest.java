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
}

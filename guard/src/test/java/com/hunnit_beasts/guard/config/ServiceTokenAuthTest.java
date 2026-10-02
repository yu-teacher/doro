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
        return callRest(properties, "POST", path, token);
    }

    private int callRest(ServiceAuthProperties properties, String method, String path, String token) throws Exception {
        ServiceTokenFilter filter = new ServiceTokenFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
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

    private ServiceAuthProperties withScopes(ServiceAuthProperties.Mode mode) {
        ServiceAuthProperties properties = new ServiceAuthProperties();
        properties.setMode(mode);
        properties.setServiceToken(TOKEN);
        properties.setServiceTokens("auth:" + AUTH_TOKEN + ", blog:" + BLOG_TOKEN + ":schema-write");
        return properties;
    }

    @Test
    @DisplayName("스키마 교체(POST /schema)는 schema-write 권한이 있는 호출자만: 없으면 403, 있으면 통과")
    void schemaWriteRequiresScope() throws Exception {
        ServiceAuthProperties enforce = withScopes(ServiceAuthProperties.Mode.ENFORCE);
        assertThat(callRest(enforce, "POST", "/api/v1/guard/schema", AUTH_TOKEN)).isEqualTo(403);
        assertThat(callRest(enforce, "POST", "/api/v1/guard/schema", BLOG_TOKEN)).isEqualTo(200);
        assertThat(callRest(enforce, "PUT", "/api/v1/guard/schema", AUTH_TOKEN)).isEqualTo(403);
        assertThat(callRest(enforce, "DELETE", "/api/v1/guard/schema/anything", AUTH_TOKEN)).isEqualTo(403);
    }

    @Test
    @DisplayName("스키마 조회(GET)와 다른 가드 API 는 권한 없는 호출자도 사용한다")
    void readsAndOtherEndpointsDoNotNeedTheScope() throws Exception {
        ServiceAuthProperties enforce = withScopes(ServiceAuthProperties.Mode.ENFORCE);
        assertThat(callRest(enforce, "GET", "/api/v1/guard/schema", AUTH_TOKEN)).isEqualTo(200);
        assertThat(callRest(enforce, "POST", "/api/v1/guard/check", AUTH_TOKEN)).isEqualTo(200);
        assertThat(callRest(enforce, "POST", "/api/v1/guard/tuples", AUTH_TOKEN)).isEqualTo(200);
    }

    @Test
    @DisplayName("공유 토큰은 이전과 같이 모든 권한을 갖고, 토큰이 없거나 틀리면 스키마 요청도 401")
    void sharedTokenKeepsEveryScopeAndAnonymousIsStill401() throws Exception {
        ServiceAuthProperties enforce = withScopes(ServiceAuthProperties.Mode.ENFORCE);
        assertThat(callRest(enforce, "POST", "/api/v1/guard/schema", TOKEN)).isEqualTo(200);
        assertThat(callRest(enforce, "POST", "/api/v1/guard/schema", null)).isEqualTo(401);
        assertThat(callRest(enforce, "POST", "/api/v1/guard/schema", "wrong")).isEqualTo(401);
    }

    @Test
    @DisplayName("WARN 은 권한이 없어도 통과시키고 기록만 한다")
    void warnModeOnlyLogsMissingScope() throws Exception {
        assertThat(callRest(withScopes(ServiceAuthProperties.Mode.WARN), "POST", "/api/v1/guard/schema", AUTH_TOKEN)).isEqualTo(200);
    }

    @Test
    @DisplayName("알 수 없는 권한 이름은 기동 시점에 실패한다")
    void unknownScopeFailsFast() {
        ServiceAuthProperties properties = new ServiceAuthProperties();
        assertThatThrownBy(() -> properties.setServiceTokens("blog:" + BLOG_TOKEN + ":schema-wrte"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setServiceTokens("blog:" + BLOG_TOKEN + ":"))
                .isInstanceOf(IllegalArgumentException.class);
        properties.setServiceTokens("blog:" + BLOG_TOKEN + ":schema-write");
        assertThat(properties.hasScope("blog", ServiceAuthProperties.SCOPE_SCHEMA_WRITE)).isTrue();
        assertThat(properties.hasScope("auth", ServiceAuthProperties.SCOPE_SCHEMA_WRITE)).isFalse();
    }
}

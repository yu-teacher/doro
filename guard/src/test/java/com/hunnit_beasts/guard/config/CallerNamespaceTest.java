package com.hunnit_beasts.guard.config;

import com.hunnit_beasts.guard.common.exception.ErrorCode;
import com.hunnit_beasts.guard.common.exception.GuardException;
import com.hunnit_beasts.guard.core.dsl.parser.DslParser;
import com.hunnit_beasts.guard.core.dsl.service.SchemaService;
import com.hunnit_beasts.guard.domain.tuple.dto.TupleDto;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 호출자별 네임스페이스 제한: 설정 파싱, 튜플·스키마 검사, gRPC 호출자 전달. */
class CallerNamespaceTest {

    private static final String BLOG_TOKEN = "blog-token-0123456789abcdef0123456789";
    private static final String AUTH_TOKEN = "auth-token-0123456789abcdef0123456789";
    private static final String GAMES_TOKEN = "games-token-0123456789abcdef01234567";

    private static final String BASE_SCHEMA = """
            type user {
              relation manager: user | system#admin
            }
            type system {
              relation admin: user
            }
            type blog_post {
              relation author: user
            }
            """;

    private static ServiceAuthProperties properties(ServiceAuthProperties.Mode mode, String spec) {
        ServiceAuthProperties properties = new ServiceAuthProperties();
        properties.setMode(mode);
        properties.setServiceTokens(spec);
        return properties;
    }

    private static String spec() {
        return "auth:" + AUTH_TOKEN + ",blog:" + BLOG_TOKEN + ":schema-write:blog_*,games:" + GAMES_TOKEN + "::games_game+games_extra*";
    }

    private static CallerNamespaceGuard guard(ServiceAuthProperties properties, String activeDsl) {
        DslParser parser = new DslParser();
        SchemaService schemaService = mock(SchemaService.class);
        when(schemaService.getActiveSchema()).thenReturn(parser.parse(activeDsl));
        return new CallerNamespaceGuard(properties, schemaService, parser);
    }

    private static TupleDto tuple(String namespace) {
        return TupleDto.of(namespace, "1", "author", "user", "u1");
    }

    @Test
    @DisplayName("설정: 이름 또는 접두사 네임스페이스를 읽고, 소유 여부를 판단한다")
    void parsesNamespaces() {
        ServiceAuthProperties p = properties(ServiceAuthProperties.Mode.ENFORCE, spec());
        assertThat(p.isNamespaceRestricted("blog")).isTrue();
        assertThat(p.ownsNamespace("blog", "blog_post")).isTrue();
        assertThat(p.ownsNamespace("blog", "blogger")).as("접두사는 blog_ 까지 포함해야 한다").isFalse();
        assertThat(p.ownsNamespace("blog", "system")).isFalse();
        assertThat(p.ownsNamespace("games", "games_game")).isTrue();
        assertThat(p.ownsNamespace("games", "games_extra_x")).isTrue();
        assertThat(p.ownsNamespace("games", "games_other")).isFalse();
        // 네임스페이스가 없는 호출자와 공유 토큰은 제한이 없다
        assertThat(p.isNamespaceRestricted("auth")).isFalse();
        assertThat(p.ownsNamespace("auth", "system")).isTrue();
        assertThat(p.isNamespaceRestricted(ServiceAuthProperties.SHARED_CALLER)).isFalse();
        assertThat(p.hasScope("blog", ServiceAuthProperties.SCOPE_SCHEMA_WRITE)).isTrue();
        assertThat(p.hasScope("games", ServiceAuthProperties.SCOPE_SCHEMA_WRITE)).as("권한 칸을 비워도 네임스페이스는 읽는다").isFalse();
    }

    @Test
    @DisplayName("설정: 단독 *, 대문자, 중복, 겹치는 네임스페이스는 기동 시점에 거부한다")
    void rejectsBadNamespaces() {
        for (String bad : new String[]{"*", "Blog_*", "blog_*+blog_*", "bl og", "_blog", "blog_*+"}) {
            assertThatThrownBy(() -> new ServiceAuthProperties().setServiceTokens("blog:" + BLOG_TOKEN + "::" + bad))
                    .as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        // 서로 다른 호출자가 같은 영역을 주장하면 소유자가 모호하다
        assertThatThrownBy(() -> new ServiceAuthProperties()
                .setServiceTokens("blog:" + BLOG_TOKEN + "::blog_*,games:" + GAMES_TOKEN + "::blog_post"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("overlap");
        assertThatThrownBy(() -> new ServiceAuthProperties()
                .setServiceTokens("blog:" + BLOG_TOKEN + "::blog_*,games:" + GAMES_TOKEN + "::blog_x*"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ENFORCE: 자기 네임스페이스 튜플은 쓰고, 다른 서비스·IAM 네임스페이스는 403(NAMESPACE_FORBIDDEN)")
    void enforceTupleWrites() {
        CallerNamespaceGuard guard = guard(properties(ServiceAuthProperties.Mode.ENFORCE, spec()), BASE_SCHEMA);
        assertThatCode(() -> guard.requireTupleAccess("blog", "write", List.of(tuple("blog_post")))).doesNotThrowAnyException();
        for (String foreign : new String[]{"system", "user", "games_game", "blogger"}) {
            assertThatThrownBy(() -> guard.requireTupleAccess("blog", "write", List.of(tuple(foreign))))
                    .as(foreign)
                    .isInstanceOfSatisfying(GuardException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NAMESPACE_FORBIDDEN))
                    .hasMessageNotContaining(foreign);
        }
    }

    @Test
    @DisplayName("ENFORCE: 배치에 남의 네임스페이스가 하나라도 섞이면 전체를 거부한다")
    void mixedBatchIsRejectedWhole() {
        CallerNamespaceGuard guard = guard(properties(ServiceAuthProperties.Mode.ENFORCE, spec()), BASE_SCHEMA);
        assertThatThrownBy(() -> guard.requireTupleAccess("blog", "write", List.of(tuple("blog_post"), tuple("system"))))
                .isInstanceOf(GuardException.class);
    }

    @Test
    @DisplayName("제한이 없는 호출자(IAM)·공유 토큰·호출자 불명·OFF·WARN 은 막지 않는다")
    void unrestrictedCases() {
        CallerNamespaceGuard enforce = guard(properties(ServiceAuthProperties.Mode.ENFORCE, spec()), BASE_SCHEMA);
        assertThatCode(() -> enforce.requireTupleAccess("auth", "write", List.of(tuple("system")))).doesNotThrowAnyException();
        assertThatCode(() -> enforce.requireTupleAccess(ServiceAuthProperties.SHARED_CALLER, "write", List.of(tuple("system")))).doesNotThrowAnyException();
        assertThatCode(() -> enforce.requireTupleAccess(null, "write", List.of(tuple("system")))).doesNotThrowAnyException();

        CallerNamespaceGuard warn = guard(properties(ServiceAuthProperties.Mode.WARN, spec()), BASE_SCHEMA);
        assertThatCode(() -> warn.requireTupleAccess("blog", "write", List.of(tuple("system")))).as("WARN 은 로그만").doesNotThrowAnyException();
        CallerNamespaceGuard off = guard(properties(ServiceAuthProperties.Mode.OFF, spec()), BASE_SCHEMA);
        assertThatCode(() -> off.requireTupleAccess("blog", "write", List.of(tuple("system")))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("스키마: 자기 타입만 바꾸면 통과, 남의 타입을 바꾸거나 지우거나 새로 만들면 거부")
    void schemaChanges() {
        CallerNamespaceGuard guard = guard(properties(ServiceAuthProperties.Mode.ENFORCE, spec()), BASE_SCHEMA);

        String ownChange = BASE_SCHEMA.replace("relation author: user", "relation author: user\n  relation editor: author")
                + "\ntype blog_comment {\n  relation author: user\n}\n";
        assertThatCode(() -> guard.requireSchemaChange("blog", ownChange)).doesNotThrowAnyException();
        assertThatCode(() -> guard.requireSchemaChange("blog", BASE_SCHEMA)).as("그대로 다시 등록").doesNotThrowAnyException();

        // 다른 서비스 타입에 권한을 심으려는 시도
        String hijack = BASE_SCHEMA.replace("relation admin: user", "relation admin: user | blog_post#author");
        String dropForeign = BASE_SCHEMA.replace("type system {\n  relation admin: user\n}\n", "");
        String addForeign = BASE_SCHEMA + "\ntype games_game {\n  relation admin: user\n}\n";
        for (String dsl : new String[]{hijack, dropForeign, addForeign}) {
            assertThatThrownBy(() -> guard.requireSchemaChange("blog", dsl))
                    .isInstanceOfSatisfying(GuardException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NAMESPACE_FORBIDDEN));
        }
        // IAM 은 제한이 없다
        assertThatCode(() -> guard.requireSchemaChange("auth", hijack)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("gRPC: 인증된 호출자 이름이 핸들러 컨텍스트로 전달된다")
    @SuppressWarnings("unchecked")
    void grpcCallerReachesTheHandler() {
        ServiceAuthProperties p = properties(ServiceAuthProperties.Mode.ENFORCE, spec());
        ServiceTokenServerInterceptor interceptor = new ServiceTokenServerInterceptor(p);
        ServerCall<Object, Object> call = mock(ServerCall.class);
        MethodDescriptor<Object, Object> descriptor = MethodDescriptor.<Object, Object>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName("doro.guard.v1.GuardService/WriteTuples")
                .setRequestMarshaller(mock(MethodDescriptor.Marshaller.class))
                .setResponseMarshaller(mock(MethodDescriptor.Marshaller.class))
                .build();
        when(call.getMethodDescriptor()).thenReturn(descriptor);

        AtomicReference<String> seen = new AtomicReference<>();
        ServerCallHandler<Object, Object> next = (c, h) -> {
            seen.set(ServiceTokenServerInterceptor.CALLER.get());
            return new ServerCall.Listener<>() { };
        };
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("x-doro-service-token", Metadata.ASCII_STRING_MARSHALLER), BLOG_TOKEN);
        interceptor.interceptCall(call, headers, next);

        assertThat(seen.get()).isEqualTo("blog");
        assertThat(ServiceTokenServerInterceptor.CALLER.get(Context.current())).as("호출이 끝나면 컨텍스트가 되돌아간다").isNull();
    }
}

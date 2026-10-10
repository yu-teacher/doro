package com.hunnit_beasts.guard.config;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/** gRPC 호출에 서비스 토큰 검증을 적용한다. */
@Slf4j
@RequiredArgsConstructor
public class ServiceTokenServerInterceptor implements ServerInterceptor {

    private static final Metadata.Key<String> TOKEN_KEY =
            Metadata.Key.of(ServiceAuthProperties.HEADER_NAME.toLowerCase(), Metadata.ASCII_STRING_MARSHALLER);

    /** 인증된 호출자 이름. 인증이 꺼져 있거나 토큰이 없으면(WARN) 비어 있다. */
    public static final Context.Key<String> CALLER = Context.key("doro-guard-caller");

    private final ServiceAuthProperties properties;
    private final RateLimitedWarn rateLimitedWarn = new RateLimitedWarn();

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        if (!properties.isActive()) {
            return next.startCall(call, headers);
        }
        String method = call.getMethodDescriptor().getFullMethodName();
        Optional<String> caller = properties.authenticate(headers.get(TOKEN_KEY));
        if (caller.isPresent()) {
            if (properties.isSharedTokenDeprecated(caller.get())) {
                rateLimitedWarn.warn(log, "grpc-shared:" + method,
                        ServiceAuthProperties.SHARED_TOKEN_WARNING + ": transport=grpc, method={}", method);
            }
            return Contexts.interceptCall(Context.current().withValue(CALLER, caller.get()), call, headers, next);
        } else {
            rateLimitedWarn.warn(log, "grpc:" + method,
                    "Guard gRPC call without a valid service token: method={}, mode={}", method, properties.getMode());
            if (properties.getMode() == ServiceAuthProperties.Mode.ENFORCE) {
                call.close(Status.UNAUTHENTICATED.withDescription("service token required"), new Metadata());
                return new ServerCall.Listener<>() { };
            }
        }
        return next.startCall(call, headers);
    }
}

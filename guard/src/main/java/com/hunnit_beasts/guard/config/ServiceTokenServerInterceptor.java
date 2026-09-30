package com.hunnit_beasts.guard.config;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** gRPC 호출에 서비스 토큰 검증을 적용한다. */
@Slf4j
@RequiredArgsConstructor
public class ServiceTokenServerInterceptor implements ServerInterceptor {

    private static final Metadata.Key<String> TOKEN_KEY =
            Metadata.Key.of(ServiceAuthProperties.HEADER_NAME.toLowerCase(), Metadata.ASCII_STRING_MARSHALLER);

    private final ServiceAuthProperties properties;

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        if (properties.isActive() && !properties.matches(headers.get(TOKEN_KEY))) {
            log.warn("Guard gRPC call without a valid service token: method={}, mode={}",
                    call.getMethodDescriptor().getFullMethodName(), properties.getMode());
            if (properties.getMode() == ServiceAuthProperties.Mode.ENFORCE) {
                call.close(Status.UNAUTHENTICATED.withDescription("service token required"), new Metadata());
                return new ServerCall.Listener<>() { };
            }
        }
        return next.startCall(call, headers);
    }
}

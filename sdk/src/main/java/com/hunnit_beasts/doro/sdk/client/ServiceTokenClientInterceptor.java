package com.hunnit_beasts.doro.sdk.client;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

/** Guard 서비스 토큰(X-Doro-Service-Token)을 모든 gRPC 호출 메타데이터에 붙인다. */
public class ServiceTokenClientInterceptor implements ClientInterceptor {

    private static final Metadata.Key<String> TOKEN_KEY =
            Metadata.Key.of("x-doro-service-token", Metadata.ASCII_STRING_MARSHALLER);

    private final String serviceToken;

    public ServiceTokenClientInterceptor(String serviceToken) {
        this.serviceToken = serviceToken;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                headers.put(TOKEN_KEY, serviceToken);
                super.start(responseListener, headers);
            }
        };
    }
}

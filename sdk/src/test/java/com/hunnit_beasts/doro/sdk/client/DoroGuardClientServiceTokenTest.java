package com.hunnit_beasts.doro.sdk.client;

import com.hunnit_beasts.guard.interfaces.grpc.CheckRequest;
import com.hunnit_beasts.guard.interfaces.grpc.CheckResponse;
import com.hunnit_beasts.guard.interfaces.grpc.GuardServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DoroGuardClientServiceTokenTest {

    private static final Metadata.Key<String> TOKEN_KEY =
            Metadata.Key.of("x-doro-service-token", Metadata.ASCII_STRING_MARSHALLER);

    private Server server;
    private DoroGuardClient client;
    private ManagedChannel channel;

    @AfterEach
    void tearDown() {
        // 채널을 바로 닫는다. DoroGuardClient.shutdown() 은 채널이 끝나길 최대 2초 기다리는데, 서버가 살아 있는 인프로세스 채널은
        // 그 시간을 항상 채운다(테스트마다 2초). 서버를 먼저 내리면 gRPC 버전이 섞여 있어 NoSuchMethodError 가 난다(백로그 참고).
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow();
        }
    }

    private AtomicReference<String> startServer(String name) throws Exception {
        AtomicReference<String> seenToken = new AtomicReference<>();
        ServerInterceptor capture = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                seenToken.set(headers.get(TOKEN_KEY));
                return next.startCall(call, headers);
            }
        };
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .intercept(capture)
                .addService(new GuardServiceGrpc.GuardServiceImplBase() {
                    @Override
                    public void check(CheckRequest request, StreamObserver<CheckResponse> responseObserver) {
                        responseObserver.onNext(CheckResponse.newBuilder().setAllowed(true).build());
                        responseObserver.onCompleted();
                    }
                })
                .build()
                .start();
        return seenToken;
    }

    @Test
    @DisplayName("서비스 토큰이 설정되면 모든 gRPC 호출 메타데이터에 X-Doro-Service-Token 이 실린다")
    void sendsServiceTokenHeader() throws Exception {
        AtomicReference<String> seen = startServer("token-on");
        channel = InProcessChannelBuilder.forName("token-on").directExecutor().build();
        client = new DoroGuardClient(channel, 3, "secret-token");

        assertThat(client.check("doc", "1", "viewer", "u1")).isTrue();
        assertThat(seen.get()).isEqualTo("secret-token");
    }

    @Test
    @DisplayName("서비스 토큰이 없으면 헤더를 보내지 않는다 (기존 동작 유지)")
    void sendsNoHeaderWithoutToken() throws Exception {
        AtomicReference<String> seen = startServer("token-off");
        channel = InProcessChannelBuilder.forName("token-off").directExecutor().build();
        client = new DoroGuardClient(channel, 3);

        assertThat(client.check("doc", "1", "viewer", "u1")).isTrue();
        assertThat(seen.get()).isNull();
    }
}

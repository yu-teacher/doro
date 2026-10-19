package com.hunnit_beasts.doro.sdk.client;

import com.hunnit_beasts.doro.sdk.exception.DoroGuardUnavailableException;
import com.hunnit_beasts.guard.interfaces.grpc.ExpandRequest;
import com.hunnit_beasts.guard.interfaces.grpc.ExpandResponse;
import com.hunnit_beasts.guard.interfaces.grpc.GuardServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DoroGuardClientExpandTest {

    private static final Metadata.Key<String> TOKEN_KEY =
            Metadata.Key.of("x-doro-service-token", Metadata.ASCII_STRING_MARSHALLER);

    private Server server;
    private DoroGuardClient client;
    private ManagedChannel channel;
    private final AtomicReference<ExpandRequest> seenRequest = new AtomicReference<>();
    private final AtomicReference<String> seenToken = new AtomicReference<>();

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

    private void start(String name, boolean fail, String serviceToken) throws Exception {
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
                    public void expand(ExpandRequest request, StreamObserver<ExpandResponse> observer) {
                        seenRequest.set(request);
                        if (fail) {
                            observer.onError(Status.INTERNAL.asRuntimeException());
                            return;
                        }
                        observer.onNext(ExpandResponse.newBuilder().setTreeJson("{\"root\":\"tree\"}").build());
                        observer.onCompleted();
                    }
                })
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        client = new DoroGuardClient(channel, 3, serviceToken);
    }

    @Test
    @DisplayName("expand 는 요청 필드를 전달하고 tree_json 을 반환하며 서비스 토큰을 싣는다")
    void expandReturnsTreeJson() throws Exception {
        start("expand-ok", false, "secret-token");

        assertThat(client.expand("doc", "1", "viewer")).isEqualTo("{\"root\":\"tree\"}");
        assertThat(client.expandOrThrow("doc", "1", "viewer")).isEqualTo("{\"root\":\"tree\"}");
        assertThat(seenRequest.get().getNamespace()).isEqualTo("doc");
        assertThat(seenRequest.get().getObjectId()).isEqualTo("1");
        assertThat(seenRequest.get().getRelation()).isEqualTo("viewer");
        assertThat(seenToken.get()).isEqualTo("secret-token");
    }

    @Test
    @DisplayName("expand 는 오류 시 null(예외 없음), expandOrThrow 는 DoroGuardUnavailableException")
    void expandFailureBehaviour() throws Exception {
        start("expand-fail", true, null);

        assertThat(client.expand("doc", "1", "viewer")).isNull();
        assertThatThrownBy(() -> client.expandOrThrow("doc", "1", "viewer"))
                .isInstanceOf(DoroGuardUnavailableException.class);
    }
}

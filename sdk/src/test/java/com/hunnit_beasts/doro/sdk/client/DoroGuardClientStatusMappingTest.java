package com.hunnit_beasts.doro.sdk.client;

import com.hunnit_beasts.doro.sdk.exception.DoroGuardInvalidRequestException;
import com.hunnit_beasts.doro.sdk.exception.DoroGuardMisconfiguredException;
import com.hunnit_beasts.doro.sdk.exception.DoroGuardUnavailableException;
import com.hunnit_beasts.doro.sdk.web.DoroExceptionHandlerAdvice;
import com.hunnit_beasts.guard.interfaces.grpc.CheckRequest;
import com.hunnit_beasts.guard.interfaces.grpc.CheckResponse;
import com.hunnit_beasts.guard.interfaces.grpc.ExpandRequest;
import com.hunnit_beasts.guard.interfaces.grpc.ExpandResponse;
import com.hunnit_beasts.guard.interfaces.grpc.GuardServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** gRPC 상태 코드별로 장애 / 입력 오류 / 설정 오류가 구분되는지 검증한다. */
class DoroGuardClientStatusMappingTest {

    private final AtomicReference<Status> failWith = new AtomicReference<>(Status.OK);
    private Server server;
    private DoroGuardClient client;
    private ManagedChannel channel;

    private void start(String name) throws Exception {
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(new GuardServiceGrpc.GuardServiceImplBase() {
                    @Override
                    public void check(CheckRequest request, StreamObserver<CheckResponse> observer) {
                        if (!failWith.get().isOk()) {
                            observer.onError(failWith.get().asRuntimeException());
                            return;
                        }
                        observer.onNext(CheckResponse.newBuilder().setAllowed(true).build());
                        observer.onCompleted();
                    }

                    @Override
                    public void expand(ExpandRequest request, StreamObserver<ExpandResponse> observer) {
                        observer.onError(failWith.get().asRuntimeException());
                    }
                }).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        client = new DoroGuardClient(channel, 3);
    }

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

    @ParameterizedTest
    @EnumSource(value = Status.Code.class, names = {"UNAVAILABLE", "DEADLINE_EXCEEDED", "RESOURCE_EXHAUSTED", "INTERNAL", "UNKNOWN"})
    @DisplayName("UNAVAILABLE 등 장애성 상태는 기존 DoroGuardUnavailableException(503 경로)")
    void outageStatuses(Status.Code code) throws Exception {
        start("map-outage-" + code);
        failWith.set(code.toStatus());

        assertThatThrownBy(() -> client.checkOrThrow("doc", "1", "viewer", "u"))
                .isExactlyInstanceOf(DoroGuardUnavailableException.class);
    }

    @Test
    @DisplayName("INVALID_ARGUMENT 는 장애가 아니라 DoroGuardInvalidRequestException")
    void invalidArgumentIsNotOutage() throws Exception {
        start("map-invalid");
        failWith.set(Status.INVALID_ARGUMENT);

        assertThatThrownBy(() -> client.checkOrThrow("doc", "1", "viewer", "u"))
                .isInstanceOf(DoroGuardInvalidRequestException.class)
                .isNotInstanceOf(DoroGuardUnavailableException.class);
        assertThatThrownBy(() -> client.expandOrThrow("doc", "1", "viewer"))
                .isInstanceOf(DoroGuardInvalidRequestException.class);
    }

    @ParameterizedTest
    @EnumSource(value = Status.Code.class, names = {"UNAUTHENTICATED", "PERMISSION_DENIED"})
    @DisplayName("서비스 토큰 거부는 DoroGuardMisconfiguredException(장애 예외의 하위 타입이라 기존 503 핸들러 호환)")
    void credentialFailuresAreMisconfiguration(Status.Code code) throws Exception {
        start("map-cred-" + code);
        failWith.set(code.toStatus());

        assertThatThrownBy(() -> client.checkOrThrow("doc", "1", "viewer", "u"))
                .isInstanceOf(DoroGuardMisconfiguredException.class)
                .isInstanceOf(DoroGuardUnavailableException.class);
        // 비예외형 check 는 기존대로 fail-closed(false)
        assertThat(client.check("doc", "1", "viewer", "u")).isFalse();
    }

    @Test
    @DisplayName("기본 핸들러: 오설정/입력 오류는 503 이 아닌 별도 코드의 500")
    void adviceDistinguishesCodes() {
        DoroExceptionHandlerAdvice advice = new DoroExceptionHandlerAdvice();

        var misconfigured = advice.handleMisconfigured(new DoroGuardMisconfiguredException("x", new RuntimeException()));
        assertThat(misconfigured.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(misconfigured.getBody()).containsEntry("code", "GUARD_MISCONFIGURED");

        var invalid = advice.handleInvalidRequest(new DoroGuardInvalidRequestException("x", new RuntimeException()));
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(invalid.getBody()).containsEntry("code", "GUARD_INVALID_REQUEST");

        var outage = advice.handleUnavailable(new DoroGuardUnavailableException("x", new RuntimeException()));
        assertThat(outage.getBody()).containsEntry("code", "GUARD_UNAVAILABLE");
    }
}

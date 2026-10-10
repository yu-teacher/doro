package com.hunnit_beasts.guard.config;

import com.hunnit_beasts.guard.interfaces.grpc.GuardGrpcService;
import com.hunnit_beasts.guard.interfaces.grpc.RelationTupleProto;
import com.hunnit_beasts.guard.interfaces.grpc.WriteTuplesRequest;
import com.hunnit_beasts.guard.interfaces.grpc.WriteTuplesResponse;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 서버에서 호출자별 네임스페이스 제한이 REST 와 gRPC 양쪽에서 지켜지는지 확인한다. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "doro.guard.security.mode=ENFORCE",
                "doro.guard.security.service-tokens=iam:iam-token-0123456789abcdef0123456789abc,"
                        + "svc:svc-token-0123456789abcdef0123456789abc:schema-write:document"
        })
@ActiveProfiles("test")
class CallerNamespaceEnforceIT {

    private static final String IAM = "iam-token-0123456789abcdef0123456789abc";
    private static final String SVC = "svc-token-0123456789abcdef0123456789abc";

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private GuardGrpcService grpc;

    private HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header(ServiceAuthProperties.HEADER_NAME, token)
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String tuple(String namespace) {
        return "[{\"namespace\":\"" + namespace + "\",\"objectId\":\"d1\",\"relation\":\"owner\","
                + "\"subjectNamespace\":\"user\",\"subjectId\":\"u-ns-test\"}]";
    }

    @Test
    @DisplayName("REST: 자기 네임스페이스는 쓰고 지우고, 남의 네임스페이스는 403 이며 아무것도 쓰이지 않는다")
    void restTuples() throws Exception {
        assertThat(send("POST", "/api/v1/guard/tuples", SVC, tuple("document")).statusCode()).isEqualTo(200);
        HttpResponse<String> denied = send("POST", "/api/v1/guard/tuples", SVC, tuple("system"));
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.body()).contains("NAMESPACE_FORBIDDEN").doesNotContain("system");
        assertThat(send("DELETE", "/api/v1/guard/tuples", SVC, tuple("system")).statusCode()).isEqualTo(403);
        assertThat(send("DELETE", "/api/v1/guard/tuples", SVC, tuple("document")).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("REST: 네임스페이스가 없는 호출자(IAM)는 어디든 쓸 수 있다")
    void unrestrictedCallerWritesAnywhere() throws Exception {
        assertThat(send("POST", "/api/v1/guard/tuples", IAM, tuple("system")).statusCode()).isEqualTo(200);
        assertThat(send("DELETE", "/api/v1/guard/tuples", IAM, tuple("system")).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("REST: 소유하지 않은 타입을 만드는 스키마 등록은 403")
    void restSchema() throws Exception {
        String active = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(send("GET", "/api/v1/guard/schema", SVC, "").body()).get("data").asText();
        // 활성 스키마의 내용은 다른 테스트가 바꿔 둘 수 있으므로(PostgreSQL 은 한 스키마를 공유한다) 내용과 무관하게 성립하는 변경을 쓴다:
        // 소유하지 않은 타입을 새로 만드는 것.
        String hijack = active + "\ntype other_service_probe {\n  relation admin: user\n}\n";
        String body = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("dsl", hijack).toString();
        assertThat(send("POST", "/api/v1/guard/schema", SVC, body).statusCode()).isEqualTo(403);
        // 통과하는 등록은 일부러 하지 않는다: PostgreSQL 모드에서는 모든 테스트가 DB 를 공유하므로 새 활성 스키마 버전이 다른 테스트의 스키마를 바꾼다.
        // 병합 등록이 통과하는 경우는 CallerNamespaceTest 가 확인한다.
    }

    private StatusRuntimeException grpcWrite(String caller, String namespace) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        StreamObserver<WriteTuplesResponse> observer = new StreamObserver<>() {
            @Override public void onNext(WriteTuplesResponse value) { }
            @Override public void onError(Throwable t) { error.set(t); }
            @Override public void onCompleted() { }
        };
        WriteTuplesRequest request = WriteTuplesRequest.newBuilder().addTuples(RelationTupleProto.newBuilder()
                .setNamespace(namespace).setObjectId("d2").setRelation("owner")
                .setSubjectNamespace("user").setSubjectId("u-ns-grpc").build()).build();
        Context.current().withValue(ServiceTokenServerInterceptor.CALLER, caller).run(() -> grpc.writeTuples(request, observer));
        return (StatusRuntimeException) error.get();
    }

    @Test
    @DisplayName("gRPC: 남의 네임스페이스는 PERMISSION_DENIED, 자기 네임스페이스와 IAM 은 통과")
    void grpcTuples() throws Exception {
        StatusRuntimeException denied = grpcWrite("svc", "system");
        assertThat(denied).isNotNull();
        assertThat(denied.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
        assertThat(grpcWrite("svc", "document")).isNull();
        assertThat(grpcWrite("iam", "system")).isNull();
        // 공유 DB 에 튜플을 남기지 않는다
        for (String namespace : new String[]{"document", "system"}) {
            send("DELETE", "/api/v1/guard/tuples", IAM, "[{\"namespace\":\"" + namespace + "\",\"objectId\":\"d2\",\"relation\":\"owner\","
                    + "\"subjectNamespace\":\"user\",\"subjectId\":\"u-ns-grpc\"}]");
        }
    }
}

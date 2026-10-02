package com.hunnit_beasts.guard.interfaces.grpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.testing.GrpcCleanupRule;
import org.junit.Rule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class GuardGrpcServiceTest {

    @Autowired
    private GuardGrpcService guardGrpcService;

    @Autowired
    private com.hunnit_beasts.guard.domain.tuple.repository.RelationTupleRepository tupleRepository;

    @Autowired
    private com.hunnit_beasts.guard.core.engine.CheckEngine checkEngine;

    @Autowired
    private com.hunnit_beasts.guard.core.dsl.service.SchemaService schemaService;

    @Autowired
    private com.hunnit_beasts.guard.core.engine.ExpandEngine expandEngine;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private GuardServiceGrpc.GuardServiceBlockingStub blockingStub;

    @BeforeEach
    void setUp() throws Exception {
        tupleRepository.deleteAll();
        checkEngine.invalidateCache();
        schemaService.resetToDefault();
        String serverName = InProcessServerBuilder.generateName();
        InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(guardGrpcService)
                .build()
                .start();

        blockingStub = GuardServiceGrpc.newBlockingStub(
                InProcessChannelBuilder.forName(serverName).directExecutor().build()
        );
    }

    @Test
    @DisplayName("gRPC RPC 통신 테스트: Protobuf 기반 WriteTuples ➡️ Check ➡️ DeleteTuples 정상 호출 검증")
    void testGrpcLifecycle() {
        // 1. WriteTuples RPC
        WriteTuplesRequest writeReq = WriteTuplesRequest.newBuilder()
                .addTuples(RelationTupleProto.newBuilder()
                        .setNamespace("document")
                        .setObjectId("grpc-doc")
                        .setRelation("owner")
                        .setSubjectNamespace("user")
                        .setSubjectId("grpc-user")
                        .build())
                .build();

        WriteTuplesResponse writeResp = blockingStub.writeTuples(writeReq);
        assertThat(writeResp.getWrittenCount()).isEqualTo(1);

        // 2. Check RPC
        CheckRequest checkReq = CheckRequest.newBuilder()
                .setNamespace("document")
                .setObjectId("grpc-doc")
                .setRelation("viewer")
                .setSubjectNamespace("user")
                .setSubjectId("grpc-user")
                .build();

        CheckResponse checkResp = blockingStub.check(checkReq);
        assertThat(checkResp.getAllowed()).isTrue();

        // 3. DeleteTuples RPC
        DeleteTuplesRequest deleteReq = DeleteTuplesRequest.newBuilder()
                .addTuples(RelationTupleProto.newBuilder()
                        .setNamespace("document")
                        .setObjectId("grpc-doc")
                        .setRelation("owner")
                        .setSubjectNamespace("user")
                        .setSubjectId("grpc-user")
                        .build())
                .build();

        DeleteTuplesResponse deleteResp = blockingStub.deleteTuples(deleteReq);
        assertThat(deleteResp.getDeletedCount()).isEqualTo(1);

        // 4. 재검사: allowed: false
        CheckResponse afterDeleteCheck = blockingStub.check(checkReq);
        assertThat(afterDeleteCheck.getAllowed()).isFalse();
    }

    // ---------------- G3: 입력 검증 (REST 와 같은 기준) ----------------

    private static RelationTupleProto.Builder validTuple() {
        return RelationTupleProto.newBuilder().setNamespace("document").setObjectId("d1").setRelation("owner")
                .setSubjectNamespace("user").setSubjectId("u1");
    }

    private static void assertInvalidArgument(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    @DisplayName("gRPC Check: 빈 값/길이 초과는 INTERNAL 이 아니라 INVALID_ARGUMENT")
    void checkRejectsBlankAndOverlong() {
        assertInvalidArgument(() -> blockingStub.check(CheckRequest.newBuilder().setObjectId("d").setRelation("r")
                .setSubjectNamespace("user").setSubjectId("u").build()));
        assertInvalidArgument(() -> blockingStub.check(CheckRequest.newBuilder().setNamespace("document").setObjectId("d")
                .setRelation("viewer").setSubjectNamespace("user").setSubjectId("u".repeat(129)).build()));
        assertInvalidArgument(() -> blockingStub.check(CheckRequest.newBuilder().setNamespace("document").setObjectId("d")
                .setRelation("viewer").setSubjectNamespace("user").setSubjectId("u").setSubjectRelation("r".repeat(65)).build()));
    }

    @Test
    @DisplayName("gRPC WriteTuples/DeleteTuples: 빈 값/길이 초과는 INVALID_ARGUMENT 이고 아무것도 쓰지 않는다")
    void writeAndDeleteRejectBlankAndOverlong() {
        assertInvalidArgument(() -> blockingStub.writeTuples(WriteTuplesRequest.newBuilder()
                .addTuples(validTuple()).addTuples(validTuple().setSubjectId(" ")).build()));
        assertInvalidArgument(() -> blockingStub.writeTuples(WriteTuplesRequest.newBuilder()
                .addTuples(validTuple().setNamespace("n".repeat(65))).build()));
        assertInvalidArgument(() -> blockingStub.writeTuples(WriteTuplesRequest.newBuilder()
                .addTuples(validTuple().setObjectId("o".repeat(129))).build()));
        assertInvalidArgument(() -> blockingStub.deleteTuples(DeleteTuplesRequest.newBuilder()
                .addTuples(validTuple().setRelation("")).build()));
        assertThat(tupleRepository.count()).isZero();
    }

    // ---------------- G7: Expand ----------------

    private JsonNode expand(String ns, String id, String rel) throws Exception {
        ExpandResponse response = blockingStub.expand(ExpandRequest.newBuilder()
                .setNamespace(ns).setObjectId(id).setRelation(rel).build());
        return objectMapper.readTree(response.getTreeJson());
    }

    private void write(String ns, String id, String rel, String subNs, String subId, String subRel) {
        blockingStub.writeTuples(WriteTuplesRequest.newBuilder().addTuples(RelationTupleProto.newBuilder()
                .setNamespace(ns).setObjectId(id).setRelation(rel).setSubjectNamespace(subNs).setSubjectId(subId)
                .setSubjectRelation(subRel == null ? "" : subRel)).build());
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static JsonNode child(JsonNode node, String object) {
        for (JsonNode c : node.get("children")) {
            if (object.equals(c.get("object").asText())) {
                return c;
            }
        }
        throw new AssertionError("child not found: " + object + " in " + node);
    }

    @Test
    @DisplayName("Expand: 직접 튜플 주체는 subjects 에, 단일 직접 항 릴레이션은 leaf")
    void expandDirectSubjects() throws Exception {
        write("document", "d1", "owner", "user", "alice", null);
        write("document", "d1", "owner", "user", "bob", null);

        JsonNode tree = expand("document", "d1", "owner");

        assertThat(tree.get("object").asText()).isEqualTo("document:d1#owner");
        assertThat(tree.get("type").asText()).isEqualTo("leaf");
        assertThat(texts(tree.get("subjects"))).containsExactly("user:alice", "user:bob");
        assertThat(tree.get("children")).isEmpty();
        assertThat(tree.has("truncated")).isFalse();
    }

    @Test
    @DisplayName("Expand: union 은 userset 주체와 computed 릴레이션(editor)을 자식으로 전개한다")
    void expandUnionWithComputedAndUserset() throws Exception {
        write("document", "d1", "viewer", "user", "v", null);
        write("document", "d1", "editor", "user", "e", null);
        write("document", "d1", "viewer", "group", "eng", "member");
        write("group", "eng", "member", "user", "g", null);

        JsonNode tree = expand("document", "d1", "viewer");

        assertThat(tree.get("type").asText()).isEqualTo("union");
        assertThat(texts(tree.get("subjects"))).containsExactlyInAnyOrder("user:v", "group:eng#member");

        JsonNode group = child(tree, "group:eng#member");
        assertThat(texts(group.get("subjects"))).containsExactly("user:g");

        JsonNode computed = child(tree, "document:d1#editor");
        assertThat(computed.get("type").asText()).isEqualTo("computed");
        JsonNode editor = child(computed, "document:d1#editor");
        assertThat(texts(editor.get("subjects"))).containsExactly("user:e");
    }

    @Test
    @DisplayName("Expand: TTU 는 tupleset 릴레이션(parent) 튜플의 부모 객체 릴레이션을 전개한다")
    void expandTupleToUserset() throws Exception {
        write("document", "d1", "parent", "folder", "f1", null);
        write("folder", "f1", "viewer", "user", "fv", null);

        JsonNode tree = expand("document", "d1", "viewer");

        JsonNode ttu = child(tree, "document:d1#parent->viewer");
        assertThat(ttu.get("type").asText()).isEqualTo("ttu");
        JsonNode folder = child(ttu, "folder:f1#viewer");
        assertThat(texts(folder.get("subjects"))).containsExactly("user:fv");
    }

    @Test
    @DisplayName("Expand: 순환(group:a#member <-> group:b#member)도 끝나고 truncated 가 아니다")
    void expandIsCycleSafe() throws Exception {
        write("group", "a", "member", "group", "b", "member");
        write("group", "b", "member", "group", "a", "member");
        write("group", "a", "member", "user", "x", null);

        JsonNode tree = expand("group", "a", "member");

        assertThat(texts(tree.get("subjects"))).containsExactlyInAnyOrder("group:b#member", "user:x");
        JsonNode b = child(tree, "group:b#member");
        assertThat(texts(b.get("subjects"))).containsExactly("group:a#member");
        // a 는 이미 이 경로에서 전개 중이므로 다시 전개하지 않는다
        assertThat(b.get("children")).isEmpty();
        assertThat(tree.has("truncated")).isFalse();
    }

    @Test
    @DisplayName("Expand: 노드 수 상한을 넘으면 전개를 멈추고 루트에 truncated:true")
    void expandTruncatesAtNodeCap() throws Exception {
        for (int i = 0; i < 10; i++) {
            write("group", "big", "member", "group", "sub" + i, "member");
            write("group", "sub" + i, "member", "user", "u" + i, null);
        }
        Object original = ReflectionTestUtils.getField(expandEngine, "maxNodes");
        ReflectionTestUtils.setField(expandEngine, "maxNodes", 4);
        try {
            JsonNode tree = expand("group", "big", "member");

            assertThat(tree.get("truncated").asBoolean()).isTrue();
            assertThat(countNodes(tree)).isLessThanOrEqualTo(4);
        } finally {
            ReflectionTestUtils.setField(expandEngine, "maxNodes", original);
        }
        // 상한 안에서는 truncated 표시가 없다
        assertThat(expand("group", "big", "member").has("truncated")).isFalse();
    }

    private static int countNodes(JsonNode node) {
        int count = 1;
        for (JsonNode c : node.get("children")) {
            count += countNodes(c);
        }
        return count;
    }

    @Test
    @DisplayName("gRPC Expand: 빈 값/길이 초과는 INVALID_ARGUMENT")
    void expandRejectsInvalidInput() {
        assertInvalidArgument(() -> blockingStub.expand(ExpandRequest.newBuilder()
                .setObjectId("d").setRelation("viewer").build()));
        assertInvalidArgument(() -> blockingStub.expand(ExpandRequest.newBuilder()
                .setNamespace("document").setObjectId("d".repeat(129)).setRelation("viewer").build()));
    }
}

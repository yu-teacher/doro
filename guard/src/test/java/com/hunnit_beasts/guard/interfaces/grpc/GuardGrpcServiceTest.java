package com.hunnit_beasts.guard.interfaces.grpc;

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

import static org.assertj.core.api.Assertions.assertThat;

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
}

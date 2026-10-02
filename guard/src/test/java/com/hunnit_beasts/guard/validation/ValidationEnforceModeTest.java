package com.hunnit_beasts.guard.validation;

import com.hunnit_beasts.guard.common.exception.ErrorCode;
import com.hunnit_beasts.guard.common.exception.GuardException;
import com.hunnit_beasts.guard.core.dsl.service.SchemaService;
import com.hunnit_beasts.guard.domain.tuple.dto.TupleDto;
import com.hunnit_beasts.guard.domain.tuple.repository.RelationTupleRepository;
import com.hunnit_beasts.guard.domain.tuple.service.TupleService;
import com.hunnit_beasts.guard.interfaces.grpc.GuardGrpcService;
import com.hunnit_beasts.guard.interfaces.grpc.GuardServiceGrpc;
import com.hunnit_beasts.guard.interfaces.grpc.RelationTupleProto;
import com.hunnit_beasts.guard.interfaces.grpc.WriteTuplesRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** G4: doro.guard.validation.mode=ENFORCE 에서 튜플/스키마 검증이 요청을 거부한다. */
@SpringBootTest(properties = "doro.guard.validation.mode=ENFORCE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ValidationEnforceModeTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TupleService tupleService;
    @Autowired
    private SchemaService schemaService;
    @Autowired
    private RelationTupleRepository tupleRepository;
    @Autowired
    private GuardGrpcService guardGrpcService;
    @Autowired
    private com.hunnit_beasts.guard.core.engine.CheckEngine checkEngine;

    @BeforeEach
    void setUp() {
        tupleRepository.deleteAll();
        checkEngine.invalidateCache();
        schemaService.resetToDefault();
    }

    private static void assertInvalidTuple(Runnable write) {
        assertThatThrownBy(write::run)
                .isInstanceOfSatisfying(GuardException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_TUPLE));
    }

    @Test
    @DisplayName("IAM 이 쓰는 튜플(system#admin, user#manager@system#admin, user#super_manager@system#super_admin)은 schema.doro 에 통과한다")
    void iamTuplesPassAgainstDefaultSchema() {
        int written = tupleService.writeTuples(List.of(
                TupleDto.of("system", "doro", "admin", "user", "11111111-1111-1111-1111-111111111111"),
                TupleDto.of("user", "11111111-1111-1111-1111-111111111111", "manager", "system", "doro", "admin"),
                TupleDto.of("user", "11111111-1111-1111-1111-111111111111", "super_manager", "system", "doro", "super_admin")
        ));
        assertThat(written).isEqualTo(3);
    }

    @Test
    @DisplayName("선언되지 않은 타입/릴레이션/subject 릴레이션 튜플은 배치 전체가 거부되고 아무것도 쓰이지 않는다")
    void undeclaredTupleRejectsWholeBatch() {
        TupleDto good = TupleDto.of("document", "d1", "owner", "user", "u1");

        assertInvalidTuple(() -> tupleService.writeTuples(List.of(good, TupleDto.of("nope", "x", "owner", "user", "u1"))));
        assertInvalidTuple(() -> tupleService.writeTuples(List.of(good, TupleDto.of("document", "d1", "bogus", "user", "u1"))));
        assertInvalidTuple(() -> tupleService.writeTuples(List.of(good, TupleDto.of("document", "d1", "viewer", "group", "g", "bogus"))));
        assertInvalidTuple(() -> tupleService.writeTuples(List.of(good, TupleDto.of("document", "d1", "viewer", "ghost", "g", "member"))));
        assertThat(tupleRepository.count()).isZero();
    }

    @Test
    @DisplayName("REST: 검증 위반은 400 INVALID_TUPLE (표준 에러 규격)")
    void restReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/guard/tuples").contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"namespace\":\"nope\",\"objectId\":\"x\",\"relation\":\"owner\","
                                + "\"subjectNamespace\":\"user\",\"subjectId\":\"u\"}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TUPLE"))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("gRPC: 검증 위반은 INVALID_ARGUMENT")
    void grpcReturnsInvalidArgument() throws Exception {
        String name = InProcessServerBuilder.generateName();
        InProcessServerBuilder.forName(name).directExecutor().addService(guardGrpcService).build().start();
        GuardServiceGrpc.GuardServiceBlockingStub stub =
                GuardServiceGrpc.newBlockingStub(InProcessChannelBuilder.forName(name).directExecutor().build());

        assertThatThrownBy(() -> stub.writeTuples(WriteTuplesRequest.newBuilder().addTuples(RelationTupleProto.newBuilder()
                .setNamespace("nope").setObjectId("x").setRelation("owner").setSubjectNamespace("user").setSubjectId("u")).build()))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    @DisplayName("blog 스키마를 병합한 운영 형태 스키마는 ENFORCE 에서도 오류 없이 등록되고, 그 튜플도 통과한다")
    void productionShapedMergedSchemaRegistersCleanly() {
        schemaService.registerSchema(SchemaFixtures.merged());

        int written = tupleService.writeTuples(List.of(
                TupleDto.of("blog_post", "p1", "author", "user", "u1"),
                TupleDto.of("blog_post", "p1", "series", "blog_series", "s1"),
                TupleDto.of("blog_series", "s1", "author", "user", "u1"),
                TupleDto.of("blog_comment", "c1", "post", "blog_post", "p1"),
                TupleDto.of("system", "doro", "admin", "user", "u9")
        ));
        assertThat(written).isEqualTo(5);
        assertThat(checkEngine.check("blog_post", "p1", "viewer", "user", "u1", null).allowed()).isTrue();
    }

    @Test
    @DisplayName("classpath schema.doro 자체도 ENFORCE 에서 등록된다")
    void defaultSchemaRegisters() {
        assertThat(schemaService.registerSchema(SchemaFixtures.defaultSchema()).isActive()).isTrue();
    }

    @Test
    @DisplayName("스키마 등록: 전방 참조는 줄 번호와 함께 400 INVALID_SYNTAX 로 거부되고 활성 스키마는 그대로다")
    void forwardReferenceSchemaIsRejected() throws Exception {
        String before = schemaService.getActiveDslText();
        String dsl = "type user {}\ntype doc {\n  relation viewer: user | editor\n  relation editor: user\n}\n";

        assertThatThrownBy(() -> schemaService.registerSchema(dsl))
                .isInstanceOfSatisfying(GuardException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_SYNTAX);
                    assertThat(e.getMessage()).contains("line 3");
                });
        assertThat(schemaService.getActiveDslText()).isEqualTo(before);

        mockMvc.perform(post("/api/v1/guard/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dsl\":" + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(dsl) + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SYNTAX"));
    }

    @Test
    @DisplayName("스키마 등록: 이해할 수 없는 줄이 있으면 거부된다")
    void unknownLineSchemaIsRejected() {
        assertThatThrownBy(() -> schemaService.registerSchema("type user {}\nrelashun x: user\n"))
                .isInstanceOfSatisfying(GuardException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_SYNTAX);
                    assertThat(e.getMessage()).contains("line 2");
                });
    }
}

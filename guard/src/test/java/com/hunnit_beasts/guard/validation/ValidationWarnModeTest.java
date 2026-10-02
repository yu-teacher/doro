package com.hunnit_beasts.guard.validation;

import com.hunnit_beasts.guard.core.dsl.service.SchemaService;
import com.hunnit_beasts.guard.domain.tuple.dto.TupleDto;
import com.hunnit_beasts.guard.domain.tuple.repository.RelationTupleRepository;
import com.hunnit_beasts.guard.domain.tuple.service.TupleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** G4: 기본 모드(WARN)는 기존 동작을 바꾸지 않는다 - 로그만 남기고 쓰기/등록은 그대로 성공한다. */
@SpringBootTest
@ActiveProfiles("test")
class ValidationWarnModeTest {

    @Autowired
    private TupleService tupleService;
    @Autowired
    private SchemaService schemaService;
    @Autowired
    private RelationTupleRepository tupleRepository;
    @Autowired
    private com.hunnit_beasts.guard.core.engine.CheckEngine checkEngine;

    @BeforeEach
    void setUp() {
        tupleRepository.deleteAll();
        checkEngine.invalidateCache();
        schemaService.resetToDefault();
    }

    @Test
    @DisplayName("WARN: 선언되지 않은 타입/릴레이션 튜플도 기존처럼 저장되고 직접 튜플 평가 의미는 그대로다")
    void undeclaredTuplesAreStillWritten() {
        int written = tupleService.writeTuples(List.of(
                TupleDto.of("nope", "x", "whatever", "user", "u1"),
                TupleDto.of("document", "d1", "bogus", "user", "u1")
        ));
        assertThat(written).isEqualTo(2);
        assertThat(checkEngine.check("nope", "x", "whatever", "user", "u1", null).allowed()).isTrue();
    }

    @Test
    @DisplayName("WARN: 전방 참조/알 수 없는 줄이 있는 스키마도 기존처럼 등록되고 평가에 쓰인다")
    void questionableSchemaIsStillRegistered() {
        String dsl = "type user {}\ntype doc {\n  relation viewer: user | editor\n  relation editor: user\n  junk\n}\n";
        assertThat(schemaService.registerSchema(dsl).isActive()).isTrue();
        assertThat(schemaService.getActiveSchema().getType("doc")).isNotNull();
    }
}

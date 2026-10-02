package com.hunnit_beasts.guard.validation;

import com.hunnit_beasts.guard.core.dsl.service.SchemaService;
import com.hunnit_beasts.guard.domain.schema.entity.SchemaDefinition;
import com.hunnit_beasts.guard.domain.schema.repository.SchemaDefinitionRepository;
import com.hunnit_beasts.guard.domain.tuple.dto.TupleDto;
import com.hunnit_beasts.guard.domain.tuple.repository.RelationTupleRepository;
import com.hunnit_beasts.guard.domain.tuple.service.TupleService;
import com.hunnit_beasts.guard.core.engine.CheckEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** G5: DB 의 활성 스키마 버전을 메모리와 맞추는 주기 갱신(refreshFromDatabase). */
@SpringBootTest
@ActiveProfiles("test")
class SchemaRefreshTest {

    @Autowired
    private SchemaService schemaService;
    @Autowired
    private SchemaDefinitionRepository schemaRepository;
    @Autowired
    private CheckEngine checkEngine;
    @Autowired
    private TupleService tupleService;
    @Autowired
    private RelationTupleRepository tupleRepository;

    @BeforeEach
    void setUp() {
        tupleRepository.deleteAll();
        checkEngine.invalidateCache();
    }

    /** 다른 인스턴스가 새 버전을 등록한 것과 같은 DB 상태를 만든다 (이 인스턴스의 메모리는 건드리지 않음). */
    private int registerFromAnotherInstance(String dsl) {
        schemaRepository.findTopByIsActiveTrueOrderByVersionDesc().ifPresent(active -> {
            active.deactivate();
            schemaRepository.saveAndFlush(active);
        });
        int version = schemaRepository.findMaxVersion() + 1;
        schemaRepository.saveAndFlush(SchemaDefinition.builder().version(version).dslText(dsl).isActive(true).build());
        return version;
    }

    @Test
    @DisplayName("다른 인스턴스가 등록한 새 버전을 갱신이 반영하고 인가 캐시를 무효화한다")
    void refreshAppliesNewVersionAndInvalidatesCache() {
        schemaService.registerSchema("type user {}\ntype shelf {\n  relation reader: user\n}\n");
        tupleService.writeTuples(List.of(TupleDto.of("shelf", "s1", "reader", "user", "u1")));
        assertThat(checkEngine.check("shelf", "s1", "reader", "user", "u1", null).reason()).isEqualTo("ACCESS_GRANTED");
        assertThat(checkEngine.check("shelf", "s1", "reader", "user", "u1", null).reason()).isEqualTo("L1_CACHE_HIT");
        assertThat(schemaService.refreshFromDatabase()).isFalse();

        int version = registerFromAnotherInstance("type user {}\ntype shelf {\n  relation reader: user\n  relation owner: user\n}\n");
        assertThat(schemaService.getActiveSchema().getType("shelf").relations()).doesNotContainKey("owner");

        assertThat(schemaService.refreshFromDatabase()).isTrue();

        assertThat(schemaService.getActiveVersion()).isEqualTo(version);
        assertThat(schemaService.getActiveSchema().getType("shelf").relations()).containsKey("owner");
        assertThat(checkEngine.check("shelf", "s1", "reader", "user", "u1", null).reason()).isEqualTo("ACCESS_GRANTED");
        // 버전이 같으면 다시 적용하지 않는다
        assertThat(schemaService.refreshFromDatabase()).isFalse();
    }

    @Test
    @DisplayName("기동 시 폴백(classpath 기본 스키마)한 상태는 다음 갱신에서 DB 의 실제 활성 스키마로 복구된다")
    void refreshHealsBootTimeFallback() {
        schemaService.registerSchema("type user {}\ntype vault {\n  relation keeper: user\n}\n");
        schemaService.resetToDefault(); // init() 이 DB 오류로 폴백한 직후의 메모리 상태와 같다
        assertThat(schemaService.getActiveSchema().getType("vault")).isNull();

        assertThat(schemaService.refreshFromDatabase()).isTrue();

        assertThat(schemaService.getActiveSchema().getType("vault")).isNotNull();
    }
}

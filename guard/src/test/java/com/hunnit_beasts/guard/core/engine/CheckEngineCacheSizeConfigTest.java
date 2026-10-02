package com.hunnit_beasts.guard.core.engine;

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

/** G2: doro.guard.engine.cache-max-size 가 실제로 반영된다 (0 이면 캐시를 쓰지 않는다). */
@SpringBootTest(properties = "doro.guard.engine.cache-max-size=0")
@ActiveProfiles("test")
class CheckEngineCacheSizeConfigTest {

    @Autowired
    private CheckEngine checkEngine;
    @Autowired
    private TupleService tupleService;
    @Autowired
    private RelationTupleRepository tupleRepository;
    @Autowired
    private SchemaService schemaService;

    @BeforeEach
    void setUp() {
        tupleRepository.deleteAll();
        checkEngine.invalidateCache();
        schemaService.resetToDefault();
    }

    @Test
    @DisplayName("cache-max-size=0 이면 같은 질의를 반복해도 캐시 적중이 없다")
    void zeroMaxSizeDisablesCaching() {
        tupleService.writeTuples(List.of(TupleDto.of("document", "size-doc", "owner", "user", "size-user")));

        checkEngine.check("document", "size-doc", "owner", "user", "size-user", null);
        checkEngine.check("document", "size-doc", "owner", "user", "size-user", null);
        CheckEngine.CheckResult third = checkEngine.check("document", "size-doc", "owner", "user", "size-user", null);

        assertThat(third.reason()).isEqualTo("ACCESS_GRANTED");
    }
}

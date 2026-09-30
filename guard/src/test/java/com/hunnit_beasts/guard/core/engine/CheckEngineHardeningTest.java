package com.hunnit_beasts.guard.core.engine;

import com.hunnit_beasts.guard.core.dsl.service.SchemaService;
import com.hunnit_beasts.guard.domain.tuple.dto.TupleDto;
import com.hunnit_beasts.guard.domain.tuple.service.TupleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CheckEngineHardeningTest {

    private static final String SCHEMA = """
            type hd_doc {
              relation parent: hd_doc
              relation base: user
              relation blocked: parent#blocked
              relation viewer: base - blocked
            }
            """;

    @Autowired
    private CheckEngine checkEngine;

    @Autowired
    private TupleService tupleService;

    @Autowired
    private SchemaService schemaService;

    @BeforeEach
    void loadSchema() {
        schemaService.applySchema(SCHEMA);
        checkEngine.invalidateCache();
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    @Test
    @DisplayName("G2: 차집합의 제외 대상 평가가 순환으로 잘리면 허용이 아니라 거부(fail-closed)")
    void differenceIsDeniedWhenSubtractBranchIsTruncated() {
        String doc = id();
        String user = id();
        tupleService.writeTuples(List.of(
                TupleDto.of("hd_doc", doc, "base", "user", user, null),
                TupleDto.of("hd_doc", doc, "parent", "hd_doc", doc, null) // 자기 자신을 부모로 가지는 순환
        ));

        assertThat(checkEngine.check("hd_doc", doc, "viewer", "user", user, null).allowed()).isFalse();
    }

    @Test
    @DisplayName("G2: 순환이 없으면 차집합은 정상적으로 base 를 허용한다")
    void differenceAllowsBaseWithoutCycle() {
        String doc = id();
        String user = id();
        tupleService.writeTuples(List.of(TupleDto.of("hd_doc", doc, "base", "user", user, null)));

        assertThat(checkEngine.check("hd_doc", doc, "viewer", "user", user, null).allowed()).isTrue();
    }

    @Test
    @DisplayName("G2: 순환으로 잘려서 나온 거부 결과는 캐시하지 않는다")
    void truncatedResultsAreNotCached() {
        String group = id();
        String user = id();
        tupleService.writeTuples(List.of(
                TupleDto.of("hd_doc", group, "parent", "hd_doc", group, null)
        ));

        assertThat(checkEngine.check("hd_doc", group, "blocked", "user", user, null).allowed()).isFalse();
        // 잘린 결과가 캐시되지 않았으므로 두 번째 호출도 L1 캐시 히트가 아니어야 한다
        CheckEngine.CheckResult second = checkEngine.check("hd_doc", group, "blocked", "user", user, null);
        assertThat(second.reason()).isNotEqualTo("L1_CACHE_HIT");
    }

    @Test
    @DisplayName("G3: 튜플 쓰기 후에는 이전에 캐시된 거부 결과가 즉시 갱신된다")
    void cacheIsInvalidatedAfterWrite() {
        String doc = id();
        String user = id();

        assertThat(checkEngine.check("hd_doc", doc, "base", "user", user, null).allowed()).isFalse();
        tupleService.writeTuples(List.of(TupleDto.of("hd_doc", doc, "base", "user", user, null)));

        assertThat(checkEngine.check("hd_doc", doc, "base", "user", user, null).allowed()).isTrue();
    }

    @Test
    @DisplayName("G3: 스키마가 바뀌면 캐시된 결과가 무효화된다")
    void cacheIsInvalidatedOnSchemaChangeEvent() {
        String doc = id();
        String user = id();
        tupleService.writeTuples(List.of(TupleDto.of("hd_doc", doc, "base", "user", user, null)));
        assertThat(checkEngine.check("hd_doc", doc, "viewer", "user", user, null).allowed()).isTrue();

        schemaService.applySchema("""
                type hd_doc {
                  relation base: user
                  relation viewer: base
                }
                """);
        checkEngine.onSchemaChanged(new com.hunnit_beasts.guard.core.dsl.service.SchemaChangedEvent());

        CheckEngine.CheckResult afterChange = checkEngine.check("hd_doc", doc, "viewer", "user", user, null);
        assertThat(afterChange.allowed()).isTrue();
        assertThat(afterChange.reason()).isNotEqualTo("L1_CACHE_HIT");
    }

    @Test
    @DisplayName("G5: 같은 배치 안의 중복 튜플과 이미 존재하는 튜플은 한 번만 기록된다")
    void duplicateTuplesAreWrittenOnce() {
        String doc = id();
        String user = id();
        TupleDto tuple = TupleDto.of("hd_doc", doc, "base", "user", user, null);

        assertThat(tupleService.writeTuples(List.of(tuple, tuple))).isEqualTo(1);
        assertThat(tupleService.writeTuples(List.of(tuple))).isZero();
        assertThat(tupleService.deleteTuples(List.of(tuple))).isEqualTo(1);
    }
}

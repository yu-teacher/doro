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

/** G1: CheckResult.depth 는 평가 중 실제로 도달한 최대 재귀 깊이다. */
@SpringBootTest
@ActiveProfiles("test")
class CheckEngineDepthTest {

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
    @DisplayName("직접 튜플은 깊이 0, TTU 상속 경로는 실제로 도달한 최대 깊이")
    void depthReflectsRecursion() {
        tupleService.writeTuples(List.of(
                TupleDto.of("document", "direct", "owner", "user", "u1"),
                TupleDto.of("folder", "root", "viewer", "user", "david"),
                TupleDto.of("folder", "sub", "parent", "folder", "root"),
                TupleDto.of("document", "spec", "parent", "folder", "sub")
        ));

        CheckEngine.CheckResult direct = checkEngine.check("document", "direct", "owner", "user", "u1", null);
        assertThat(direct.allowed()).isTrue();
        assertThat(direct.maxDepthReached()).isZero();

        // 기본 스키마에서 viewer 는 editor(computed) -> owner(computed) 를 먼저 탐색한 뒤 parent#viewer 로 올라간다.
        // 가장 깊이 들어간 경로: document:spec#viewer(0) -> folder:sub#viewer(1) -> folder:sub#editor(2) -> folder:sub#owner(3)
        CheckEngine.CheckResult chain = checkEngine.check("document", "spec", "viewer", "user", "david", null);
        assertThat(chain.allowed()).isTrue();
        assertThat(chain.maxDepthReached()).isEqualTo(3);
        assertThat(chain.reason()).isEqualTo("ACCESS_GRANTED");
    }

    @Test
    @DisplayName("캐시 적중 결과도 같은 깊이를 돌려주고 reason 은 L1_CACHE_HIT")
    void cacheHitKeepsDepth() {
        tupleService.writeTuples(List.of(
                TupleDto.of("folder", "root", "viewer", "user", "david"),
                TupleDto.of("folder", "sub", "parent", "folder", "root")
        ));

        CheckEngine.CheckResult first = checkEngine.check("folder", "sub", "viewer", "user", "david", null);
        CheckEngine.CheckResult second = checkEngine.check("folder", "sub", "viewer", "user", "david", null);

        // folder:sub#viewer(0) -> folder:sub#editor(1) -> folder:sub#owner(2) 가 최대, 이어서 parent -> folder:root#viewer(1) 에서 직접 적중
        assertThat(first.maxDepthReached()).isEqualTo(2);
        assertThat(second.reason()).isEqualTo("L1_CACHE_HIT");
        assertThat(second.maxDepthReached()).isEqualTo(2);
    }

    @Test
    @DisplayName("거부 결과의 깊이도 실제 탐색 깊이를 반영한다")
    void deniedDepthIsReported() {
        tupleService.writeTuples(List.of(
                TupleDto.of("folder", "root", "viewer", "user", "someone-else"),
                TupleDto.of("folder", "sub", "parent", "folder", "root")
        ));
        CheckEngine.CheckResult denied = checkEngine.check("folder", "sub", "viewer", "user", "david", null);
        assertThat(denied.allowed()).isFalse();
        // folder:root#viewer(1) -> folder:root#editor(2) -> folder:root#owner(3)
        assertThat(denied.maxDepthReached()).isEqualTo(3);
    }
}

package com.hunnit_beasts.guard.core.engine;

import com.hunnit_beasts.guard.domain.tuple.dto.TupleDto;
import com.hunnit_beasts.guard.domain.tuple.service.TupleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class CheckEngineCacheAndInvalidationTest {

    @Autowired
    private CheckEngine checkEngine;

    @Autowired
    private TupleService tupleService;

    @Test
    @DisplayName("캐시 정합성 검증: L1 캐시 적중 및 튜플 쓰기/삭제 시 즉시 캐시 무효화")
    void testCacheHitAndInvalidation() {
        String ns = "document";
        String objId = "cache-test-doc";
        String rel = "owner";
        String subNs = "user";
        String subId = "cache-user";

        // 1. 초기 상태: 권한 없음 (캐시 미스 -> false 캐싱)
        CheckEngine.CheckResult res1 = checkEngine.check(ns, objId, rel, subNs, subId, null);
        assertThat(res1.allowed()).isFalse();
        assertThat(res1.reason()).isEqualTo("ACCESS_DENIED");

        // 2. 재조회: L1 캐시 히트 (L1_CACHE_HIT)
        CheckEngine.CheckResult res2 = checkEngine.check(ns, objId, rel, subNs, subId, null);
        assertThat(res2.allowed()).isFalse();
        assertThat(res2.reason()).isEqualTo("L1_CACHE_HIT");

        // 3. 튜플 쓰기: 캐시 자동 무효화 트리거
        tupleService.writeTuples(List.of(TupleDto.of(ns, objId, rel, subNs, subId)));

        // 4. 즉시 재조회: 캐시가 날아가서 DB/엔진 재평가 -> true 반영
        CheckEngine.CheckResult res3 = checkEngine.check(ns, objId, rel, subNs, subId, null);
        assertThat(res3.allowed()).isTrue();
        assertThat(res3.reason()).isEqualTo("ACCESS_GRANTED");

        // 5. 다시 조회: L1 캐시 히트 (true)
        CheckEngine.CheckResult res4 = checkEngine.check(ns, objId, rel, subNs, subId, null);
        assertThat(res4.allowed()).isTrue();
        assertThat(res4.reason()).isEqualTo("L1_CACHE_HIT");

        // 6. 튜플 삭제: 캐시 자동 무효화 트리거
        tupleService.deleteTuples(List.of(TupleDto.of(ns, objId, rel, subNs, subId)));

        // 7. 재조회: 즉각 false 반영
        CheckEngine.CheckResult res5 = checkEngine.check(ns, objId, rel, subNs, subId, null);
        assertThat(res5.allowed()).isFalse();
        assertThat(res5.reason()).isEqualTo("ACCESS_DENIED");
    }
}

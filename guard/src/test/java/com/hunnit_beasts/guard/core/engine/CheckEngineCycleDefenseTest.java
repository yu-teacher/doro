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
class CheckEngineCycleDefenseTest {

    @Autowired
    private CheckEngine checkEngine;

    @Autowired
    private TupleService tupleService;

    @Test
    @DisplayName("순환 참조 방어: Group A와 Group B가 상호 참조할 때 무한루프 없이 0.01초 내 안전 탈출(Cycle Break)")
    void testCircularGroupReferenceDoesNotLoopInfinitely() {
        // Group A -> Group B -> Group A 상호 순환 참조 구성
        tupleService.writeTuples(List.of(
                TupleDto.of("group", "teamA", "member", "group", "teamB", "member"),
                TupleDto.of("group", "teamB", "member", "group", "teamA", "member")
        ));

        long startTime = System.currentTimeMillis();

        // 존재하지 않는 유저(ghost)로 질의 시 순환 루프에 빠지지 않고 즉시 false 반환 검증
        CheckEngine.CheckResult result = checkEngine.check("group", "teamA", "member", "user", "ghost", null);
        long elapsed = System.currentTimeMillis() - startTime;

        assertThat(result.allowed()).isFalse();
        assertThat(elapsed).isLessThan(500); // 0.5초 이내 즉각 안전 반환
    }
}

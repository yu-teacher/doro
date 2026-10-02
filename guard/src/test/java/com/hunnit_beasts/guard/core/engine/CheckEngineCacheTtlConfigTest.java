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

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** G2: doro.guard.engine.cache-ttl-seconds 가 실제로 L1 캐시 TTL 에 반영된다 (기본 60초면 이 테스트는 실패한다). */
@SpringBootTest(properties = "doro.guard.engine.cache-ttl-seconds=1")
@ActiveProfiles("test")
class CheckEngineCacheTtlConfigTest {

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
    @DisplayName("cache-ttl-seconds=1 이면 1초 뒤 캐시 항목이 만료되어 다시 평가한다")
    void cacheEntryExpiresAfterConfiguredTtl() {
        tupleService.writeTuples(List.of(TupleDto.of("document", "ttl-doc", "owner", "user", "ttl-user")));

        assertThat(checkEngine.check("document", "ttl-doc", "owner", "user", "ttl-user", null).reason())
                .isEqualTo("ACCESS_GRANTED");

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(checkEngine.check("document", "ttl-doc", "owner", "user", "ttl-user", null).reason())
                        .isEqualTo("ACCESS_GRANTED"));
    }
}

package com.hunnit_beasts.guard.postgres;

import com.hunnit_beasts.guard.domain.tuple.repository.RelationTupleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영과 같은 방식(Flyway 마이그레이션 + Hibernate ddl-auto=validate)으로 실제 PostgreSQL 위에서 기동한다.
 * 기본 테스트는 H2 + create-drop 이라 마이그레이션 SQL 과 엔티티가 어긋나도, PostgreSQL 에서만 되는 SQL 이 깨져도 알 수 없다.
 */
@PostgresTest
@SpringBootTest
@ActiveProfiles("test")
class PostgresSchemaTest {

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PgTestDatabase::freshSchemaUrl);
        registry.add("spring.datasource.username", PgTestDatabase::user);
        registry.add("spring.datasource.password", PgTestDatabase::password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/vendor/{vendor}");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private RelationTupleRepository tuples;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("마이그레이션이 모두 적용되고 엔티티가 스키마와 일치한다 (이 컨텍스트가 뜬 것 자체가 validate 통과)")
    void migrationsAppliedAndEntitiesMatch() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from guard_schema_history where success = true and \"version\" in ('1', '2', '3')", Integer.class);

        assertThat(applied).isEqualTo(3);
    }

    @Test
    @DisplayName("직접 튜플 부분 유니크 인덱스(V3)가 PostgreSQL 에 실제로 만들어진다")
    void partialUniqueIndexExists() {
        String def = jdbc.queryForObject(
                "select indexdef from pg_indexes where schemaname = current_schema() and indexname = 'uq_relation_tuple_direct'",
                String.class);

        assertThat(def).containsIgnoringCase("UNIQUE").containsIgnoringCase("subject_relation IS NULL");
    }

    /** 서비스와 같이 트랜잭션 안에서 쓴다 (@Modifying 쿼리는 트랜잭션이 필요하다). */
    private int insert(String object, String subjectId, String subjectRelation) {
        Integer written = new TransactionTemplate(transactionManager).execute(status ->
                tuples.insertIfAbsent(UUID.randomUUID(), "doc", object, "viewer", "user", subjectId, subjectRelation));
        return written == null ? 0 : written;
    }

    private long count(String object, String subjectId) {
        Long n = jdbc.queryForObject(
                "select count(*) from relation_tuples where namespace='doc' and object_id=? and subject_id=?",
                Long.class, object, subjectId);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("같은 직접 튜플을 순차로 두 번 써도 한 행만 남고 두 번째는 0건 반영 (ON CONFLICT DO NOTHING)")
    void directTupleIsIdempotent() {
        assertThat(insert("seq-1", "alice", null)).isEqualTo(1);
        assertThat(insert("seq-1", "alice", null)).isZero();
        assertThat(count("seq-1", "alice")).isEqualTo(1);
    }

    @Test
    @DisplayName("userset 튜플(subject_relation 있음)도 중복되지 않는다")
    void usersetTupleIsIdempotent() {
        assertThat(insert("set-1", "eng", "member")).isEqualTo(1);
        assertThat(insert("set-1", "eng", "member")).isZero();
        assertThat(count("set-1", "eng")).isEqualTo(1);
    }

    @Test
    @DisplayName("직접 튜플과 userset 튜플은 서로 다른 튜플이다 (subject_relation NULL 규칙에 가려지지 않는다)")
    void directAndUsersetAreDistinct() {
        assertThat(insert("mix-1", "eng", null)).isEqualTo(1);
        assertThat(insert("mix-1", "eng", "member")).isEqualTo(1);
        assertThat(count("mix-1", "eng")).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 직접 튜플을 20개 스레드가 동시에 써도 정확히 한 행만 만들어진다")
    void concurrentDirectWritesCreateExactlyOneRow() throws Exception {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return insert("race-1", "bob", null);
            }));
        }
        start.countDown();
        int inserted = 0;
        for (Future<Integer> f : results) {
            inserted += f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(inserted).as("반영된 INSERT 는 한 번뿐").isEqualTo(1);
        assertThat(count("race-1", "bob")).isEqualTo(1);
    }
}

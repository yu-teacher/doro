package com.hunnit_beasts.guard.postgres;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 운영 DB 가 거친 경로를 재현한다: V1 만 적용된 상태(직접 튜플 중복이 막히지 않던 때)에 중복 행이 쌓여 있고,
 * 이후 V2(중복 정리)와 V3(부분 유니크 인덱스)가 적용된다.
 */
@PostgresTest
class PostgresDedupeMigrationTest {

    private static final String[] LOCATIONS = {"classpath:db/migration", "classpath:db/vendor/postgresql"};

    private Flyway flyway(DataSource ds, String target) {
        var config = Flyway.configure().dataSource(ds).locations(LOCATIONS).table("guard_schema_history");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private void insertTuple(JdbcTemplate jdbc, String object, String subject, String subjectRelation, String createdAt) {
        jdbc.update("insert into relation_tuples (id, namespace, object_id, relation, subject_namespace, subject_id, subject_relation, created_at) "
                + "values (?, 'doc', ?, 'viewer', 'user', ?, ?, ?::timestamptz)",
                UUID.randomUUID(), object, subject, subjectRelation, createdAt);
    }

    @Test
    @DisplayName("V1 상태에서 쌓인 중복 직접 튜플은 V2 가 가장 오래된 하나만 남기고 백업하며, V3 이후에는 다시 중복이 들어갈 수 없다")
    void dedupeThenUniqueIndex() {
        DataSource ds = PgTestDatabase.dataSource(PgTestDatabase.freshSchemaUrl());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        flyway(ds, "1").migrate();

        // V1 의 유니크 인덱스는 subject_relation 이 NULL 이면 중복을 막지 못한다 -> 같은 직접 튜플 3개가 들어간다
        insertTuple(jdbc, "dup", "alice", null, "2026-01-01T00:00:00Z"); // 이것이 남아야 한다
        insertTuple(jdbc, "dup", "alice", null, "2026-01-02T00:00:00Z");
        insertTuple(jdbc, "dup", "alice", null, "2026-01-03T00:00:00Z");
        insertTuple(jdbc, "uniq", "carol", null, "2026-01-01T00:00:00Z");      // 중복 아님
        insertTuple(jdbc, "dup", "alice", "member", "2026-01-01T00:00:00Z");   // userset 은 별개 튜플
        assertThat(jdbc.queryForObject("select count(*) from relation_tuples where object_id='dup' and subject_relation is null", Long.class)).isEqualTo(3);

        flyway(ds, null).migrate(); // V2, V3

        assertThat(jdbc.queryForObject("select count(*) from relation_tuples where object_id='dup' and subject_relation is null", Long.class))
                .as("직접 튜플 중복은 하나만").isEqualTo(1);
        assertThat(jdbc.queryForObject("select to_char(created_at at time zone 'UTC','YYYY-MM-DD') from relation_tuples where object_id='dup' and subject_relation is null", String.class))
                .as("가장 오래된 행이 남는다").isEqualTo("2026-01-01");
        assertThat(jdbc.queryForObject("select count(*) from relation_tuples_duplicates_backup", Long.class))
                .as("지워진 두 행은 백업 테이블에 보관").isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from relation_tuples where object_id='uniq'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from relation_tuples where object_id='dup' and subject_relation='member'", Long.class))
                .as("userset 튜플은 그대로").isEqualTo(1);

        assertThatThrownBy(() -> insertTuple(jdbc, "dup", "alice", null, "2026-02-01T00:00:00Z"))
                .as("V3 이후 같은 직접 튜플을 다시 넣으면 DB 가 거부한다")
                .hasRootCauseInstanceOf(SQLException.class)
                .rootCause().hasMessageContaining("uq_relation_tuple_direct");
    }
}

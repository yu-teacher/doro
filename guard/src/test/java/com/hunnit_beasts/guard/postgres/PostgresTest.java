package com.hunnit_beasts.guard.postgres;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 실제 PostgreSQL 이 필요한 테스트 표시. 기본 {@code test} 태스크(H2)에서는 제외되고 {@code postgresTest} 태스크에서만 실행된다.
 * TEST_PG_URL 이 없으면(로컬에 PostgreSQL 이 없을 때) 건너뛴다. CI 는 항상 설정한다 (scripts/ci-test.sh).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "TEST_PG_URL", matches = ".+")
public @interface PostgresTest {
}

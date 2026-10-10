package com.hunnit_beasts.auth.oauth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway 스크립트(V1~V7)만으로 만든 스키마를 Hibernate 가 ddl-auto=validate 로 엔티티와 대조한다.
 * (운영과 같은 방식의 검증을 H2 PostgreSQL 모드에서 수행한다. 실제 PostgreSQL 기동 검증은 아님)
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.url=jdbc:h2:mem:doro_iam_schema_validate;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
})
@ActiveProfiles("test")
class MigrationSchemaValidationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("V6, V7, V12 마이그레이션으로 oauth_clients 테이블(first_party, client_secret_hash 포함)이 만들어지고 엔티티 매핑과 일치한다")
    void oauthClientsTableMatchesEntity() {
        Integer columns = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.columns where table_name = 'oauth_clients'", Integer.class);
        assertThat(columns).isEqualTo(9);
    }
}

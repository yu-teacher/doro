package com.hunnit_beasts.guard.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class FlywayMigrationsAppliedTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("쉼표로 구분된 Flyway 위치가 각각 해석되어 공통 마이그레이션(V1, V2)이 실제로 적용된다")
    void commonMigrationsAreApplied() {
        Integer applied = jdbcTemplate.queryForObject(
                "select count(*) from guard_schema_history where success = true and \"version\" in ('1', '2')",
                Integer.class);

        assertThat(applied).isEqualTo(2);
    }

    @Test
    @DisplayName("위치 문자열은 쉼표 기준으로 분리되고 공백이 제거되며 {vendor} 가 DB 종류로 치환된다")
    void splitsCommaSeparatedLocations() {
        assertThat(FlywayConfig.splitLocations("classpath:db/migration, classpath:db/vendor/{vendor}", "postgresql"))
                .containsExactly("classpath:db/migration", "classpath:db/vendor/postgresql");
    }
}

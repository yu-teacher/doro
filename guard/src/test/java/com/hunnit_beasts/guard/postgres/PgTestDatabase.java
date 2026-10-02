package com.hunnit_beasts.guard.postgres;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/** 테스트마다 새 스키마를 만들어 서로 영향을 주지 않게 한다. 접속 정보는 TEST_PG_URL / TEST_PG_USER / TEST_PG_PASSWORD. */
final class PgTestDatabase {

    private PgTestDatabase() {
    }

    static String user() {
        return System.getenv().getOrDefault("TEST_PG_USER", "postgres");
    }

    static String password() {
        return System.getenv().getOrDefault("TEST_PG_PASSWORD", "");
    }

    /** 새 스키마를 만들고, 그 스키마를 기본으로 쓰는 JDBC URL 을 돌려준다 (Flyway 이력 테이블도 이 스키마에 생긴다). */
    static String freshSchemaUrl() {
        String baseUrl = System.getenv("TEST_PG_URL");
        String schema = "t_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = new DriverManagerDataSource(baseUrl, user(), password()).getConnection();
             Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        } catch (SQLException e) {
            throw new IllegalStateException("테스트 스키마를 만들 수 없다: " + baseUrl, e);
        }
        return baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    static DataSource dataSource(String url) {
        return new DriverManagerDataSource(url, user(), password());
    }
}

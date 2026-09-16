package com.hunnit_beasts.guard.config;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Slf4j
@Configuration
public class FlywayConfig {

    @Bean(initMethod = "migrate")
    public Flyway flyway(
            DataSource dataSource,
            @Value("${spring.flyway.locations:classpath:db/migration}") String locations,
            @Value("${spring.flyway.table:guard_schema_history}") String table,
            @Value("${spring.flyway.baseline-on-migrate:true}") boolean baselineOnMigrate
    ) {
        log.info("Initializing Guard Flyway migration: locations={}, table={}, baselineOnMigrate={}",
                locations, table, baselineOnMigrate);

        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(locations)
                .table(table)
                .baselineOnMigrate(baselineOnMigrate)
                .baselineVersion("0")
                .load();

        flyway.repair();
        return flyway;
    }
}

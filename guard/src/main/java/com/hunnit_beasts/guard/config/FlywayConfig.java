package com.hunnit_beasts.guard.config;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DatabaseDriver;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Configuration
public class FlywayConfig {

    @Bean
    public static BeanFactoryPostProcessor dependsOnFlywayPostProcessor() {
        return (ConfigurableListableBeanFactory beanFactory) -> {
            if (beanFactory.containsBeanDefinition("entityManagerFactory")) {
                BeanDefinition bd = beanFactory.getBeanDefinition("entityManagerFactory");
                String[] dependsOn = bd.getDependsOn();
                if (dependsOn == null) {
                    bd.setDependsOn("flyway");
                } else {
                    List<String> list = new ArrayList<>(Arrays.asList(dependsOn));
                    if (!list.contains("flyway")) {
                        list.add("flyway");
                        bd.setDependsOn(list.toArray(new String[0]));
                    }
                }
            }
        };
    }

    /**
     * 쉼표로 구분된 여러 위치를 각각의 위치로 분리하고, {vendor} 를 실제 DB 종류(postgresql, h2 ...)로 치환한다.
     * ({vendor} 치환은 Spring Boot 자동 구성의 기능이라 이 커스텀 설정에서는 직접 처리해야 한다.)
     */
    static String[] splitLocations(String locations, String vendor) {
        return Arrays.stream(locations.split(","))
                .map(String::trim)
                .filter(location -> !location.isEmpty())
                .map(location -> location.replace("{vendor}", vendor))
                .toArray(String[]::new);
    }

    private static String resolveVendor(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return DatabaseDriver.fromJdbcUrl(connection.getMetaData().getURL()).getId();
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to determine the database vendor for Flyway locations", e);
        }
    }

    @Bean
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
                .locations(splitLocations(locations, resolveVendor(dataSource)))
                .table(table)
                .baselineOnMigrate(baselineOnMigrate)
                .baselineVersion("0")
                .load();

        flyway.repair();
        flyway.migrate();
        return flyway;
    }
}

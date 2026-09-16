package com.hunnit_beasts.guard.config;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
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
                .locations(locations)
                .table(table)
                .baselineOnMigrate(baselineOnMigrate)
                .baselineVersion("0")
                .load();

        flyway.repair();
        flyway.migrate();
        return flyway;
    }
}

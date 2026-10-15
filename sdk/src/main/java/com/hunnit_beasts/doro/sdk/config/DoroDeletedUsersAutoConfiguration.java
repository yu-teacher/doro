package com.hunnit_beasts.doro.sdk.config;

import com.hunnit_beasts.doro.sdk.sync.DeletedUserHandler;
import com.hunnit_beasts.doro.sdk.sync.DeletedUsersClient;
import com.hunnit_beasts.doro.sdk.sync.DeletedUsersProperties;
import com.hunnit_beasts.doro.sdk.sync.DeletedUsersSync;
import com.hunnit_beasts.doro.sdk.sync.SyncCursorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Instant;

/**
 * 탈퇴 동기화 자동 구성. 서비스가 {@link DeletedUsersProperties}, {@link DeletedUserHandler}, {@link SyncCursorStore} 빈을 등록하면 켜진다.
 * 주기 실행은 설정의 interval/initial-delay 를 따르며, 서비스에 {@code @EnableScheduling} 이 있어야 돈다.
 */
@AutoConfiguration(after = DoroAutoConfiguration.class)
@ConditionalOnBean({DeletedUsersProperties.class, DeletedUserHandler.class, SyncCursorStore.class})
public class DoroDeletedUsersAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DeletedUsersClient deletedUsersClient(DeletedUsersProperties props,
                                                 @Value("${doro.iam.internal-url:http://localhost:28080}") String baseUrl,
                                                 @Value("${doro.iam.internal-token:}") String token) {
        return new DeletedUsersClient(baseUrl, token, props);
    }

    @Bean
    @ConditionalOnMissingBean
    public DeletedUsersSync deletedUsersSync(DeletedUsersClient client, DeletedUserHandler handler, SyncCursorStore cursors,
                                             DeletedUsersProperties props) {
        return new DeletedUsersSync(client, handler, cursors, props, Clock.systemUTC());
    }

    /** 설정의 interval/initial-delay 로 주기 실행을 등록한다(@Scheduled 는 애노테이션 값이 고정이라 쓰지 않는다). */
    @Configuration(proxyBeanMethods = false)
    static class Scheduling implements SchedulingConfigurer {

        private final DeletedUsersSync sync;
        private final DeletedUsersProperties props;

        Scheduling(DeletedUsersSync sync, DeletedUsersProperties props) {
            this.sync = sync;
            this.props = props;
        }

        @Override
        public void configureTasks(ScheduledTaskRegistrar registrar) {
            registrar.addFixedDelayTask(new FixedDelayTask(sync::scheduledRun, props.getInterval(), props.getInitialDelay()));
        }
    }
}

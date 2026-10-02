package com.hunnit_beasts.guard.validation;

import com.hunnit_beasts.guard.core.dsl.parser.DslParser;
import com.hunnit_beasts.guard.core.dsl.service.SchemaChangedEvent;
import com.hunnit_beasts.guard.core.dsl.service.SchemaService;
import com.hunnit_beasts.guard.common.validation.ValidationMode;
import com.hunnit_beasts.guard.domain.schema.entity.SchemaDefinition;
import com.hunnit_beasts.guard.domain.schema.repository.SchemaDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 갱신 스레드가 오래전에 읽은 낡은 버전으로, 방금 등록되어 메모리에 반영된 새 버전을 덮어쓰면 안 된다.
 * (버전은 항상 max+1 로만 늘어나므로 DB 버전이 메모리보다 클 때만 적용한다.)
 */
class SchemaRefreshStaleReadTest {

    private SchemaDefinitionRepository repository;
    private ApplicationEventPublisher publisher;
    private SchemaService service;

    private static SchemaDefinition definition(int version, String dsl) {
        return SchemaDefinition.builder().version(version).dslText(dsl).isActive(true).build();
    }

    @BeforeEach
    void setUp() {
        repository = mock(SchemaDefinitionRepository.class);
        publisher = mock(ApplicationEventPublisher.class);
        service = new SchemaService(new DslParser(), repository, new DefaultResourceLoader(), publisher,
                mock(PlatformTransactionManager.class), ValidationMode.WARN, 0);
    }

    @Test
    @DisplayName("DB 에서 읽은 버전이 메모리보다 낮으면(낡은 읽기) 메모리 스키마를 되돌리지 않는다")
    void staleReadDoesNotRegressTheSchema() {
        when(repository.findTopByIsActiveTrueOrderByVersionDesc())
                .thenReturn(Optional.of(definition(6, "type user {}\ntype shelf {\n  relation reader: user\n  relation owner: user\n}\n")));
        assertThat(service.refreshFromDatabase()).isTrue();
        assertThat(service.getActiveVersion()).isEqualTo(6);

        // 이전에 읽어 둔 낡은 v5 가 뒤늦게 도착한 상황
        when(repository.findTopByIsActiveTrueOrderByVersionDesc())
                .thenReturn(Optional.of(definition(5, "type user {}\ntype shelf {\n  relation reader: user\n}\n")));

        assertThat(service.refreshFromDatabase()).isFalse();
        assertThat(service.getActiveVersion()).isEqualTo(6);
        assertThat(service.getActiveSchema().getType("shelf").relations()).containsKey("owner");
    }

    @Test
    @DisplayName("더 높은 버전은 적용하고 캐시 무효화 이벤트를 한 번만 발행한다")
    void newerVersionIsAppliedOnce() {
        when(repository.findTopByIsActiveTrueOrderByVersionDesc())
                .thenReturn(Optional.of(definition(3, "type user {}\n")));
        assertThat(service.refreshFromDatabase()).isTrue();
        assertThat(service.refreshFromDatabase()).isFalse();
        verify(publisher, org.mockito.Mockito.times(1)).publishEvent(any(SchemaChangedEvent.class));
    }

    @Test
    @DisplayName("DB 에 활성 스키마가 없으면 아무 것도 하지 않는다")
    void noActiveSchemaInDbIsANoop() {
        when(repository.findTopByIsActiveTrueOrderByVersionDesc()).thenReturn(Optional.empty());
        assertThat(service.refreshFromDatabase()).isFalse();
        verify(publisher, never()).publishEvent(any());
    }
}

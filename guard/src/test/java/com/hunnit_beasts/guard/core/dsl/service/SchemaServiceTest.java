package com.hunnit_beasts.guard.core.dsl.service;

import com.hunnit_beasts.guard.common.exception.ErrorCode;
import com.hunnit_beasts.guard.common.exception.GuardException;
import com.hunnit_beasts.guard.common.validation.ValidationMode;
import com.hunnit_beasts.guard.core.dsl.parser.DslParser;
import com.hunnit_beasts.guard.domain.schema.entity.SchemaDefinition;
import com.hunnit_beasts.guard.domain.schema.repository.SchemaDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** G5: UNIQUE(version) 경합 시 재시도와 SCHEMA_CONFLICT. */
class SchemaServiceTest {

    private SchemaDefinitionRepository repository;
    private ApplicationEventPublisher publisher;
    private SchemaService service;

    @BeforeEach
    void setUp() {
        repository = mock(SchemaDefinitionRepository.class);
        publisher = mock(ApplicationEventPublisher.class);
        when(repository.findMaxVersion()).thenReturn(4);
        service = new SchemaService(new DslParser(), repository, new DefaultResourceLoader(), publisher,
                mock(PlatformTransactionManager.class), ValidationMode.WARN, 0);
    }

    @Test
    @DisplayName("유니크 위반이 두 번 나도 세 번째 시도에서 성공하고 메모리/이벤트가 반영된다")
    void retriesOnVersionConflict() {
        when(repository.saveAndFlush(any(SchemaDefinition.class)))
                .thenThrow(new DataIntegrityViolationException("dup 1"))
                .thenThrow(new DataIntegrityViolationException("dup 2"))
                .thenAnswer(inv -> inv.getArgument(0));

        SchemaDefinition saved = service.registerSchema("type user {}\n");

        assertThat(saved.getVersion()).isEqualTo(5);
        verify(repository, times(3)).saveAndFlush(any(SchemaDefinition.class));
        assertThat(service.getActiveSchema().getType("user")).isNotNull();
        assertThat(service.getActiveVersion()).isEqualTo(5);
        verify(publisher).publishEvent(any(SchemaChangedEvent.class));
    }

    @Test
    @DisplayName("세 번 모두 충돌하면 SCHEMA_CONFLICT(409) 이고 메모리 스키마는 바뀌지 않는다")
    void givesUpAfterThreeAttempts() {
        when(repository.saveAndFlush(any(SchemaDefinition.class))).thenThrow(new DataIntegrityViolationException("dup"));

        assertThatThrownBy(() -> service.registerSchema("type user {}\n"))
                .isInstanceOfSatisfying(GuardException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SCHEMA_CONFLICT);
                    assertThat(e.getErrorCode().getHttpStatus().value()).isEqualTo(409);
                });
        verify(repository, times(3)).saveAndFlush(any(SchemaDefinition.class));
        assertThat(service.getActiveSchema()).isNull();
    }
}

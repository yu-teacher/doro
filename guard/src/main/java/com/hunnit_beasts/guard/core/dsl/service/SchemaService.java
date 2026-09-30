package com.hunnit_beasts.guard.core.dsl.service;

import com.hunnit_beasts.guard.core.dsl.ast.AstNodes.SchemaAst;
import com.hunnit_beasts.guard.core.dsl.parser.DslParser;
import com.hunnit_beasts.guard.domain.schema.entity.SchemaDefinition;
import com.hunnit_beasts.guard.domain.schema.repository.SchemaDefinitionRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class SchemaService {

    private final DslParser dslParser;
    private final SchemaDefinitionRepository schemaRepository;
    private final ResourceLoader resourceLoader;
    private final ApplicationEventPublisher eventPublisher;

    private final AtomicReference<SchemaAst> activeSchemaRef = new AtomicReference<>();
    private final AtomicReference<String> activeDslTextRef = new AtomicReference<>();

    @PostConstruct
    public void init() {
        try {
            Optional<SchemaDefinition> activeInDb = schemaRepository.findTopByIsActiveTrueOrderByVersionDesc();

            if (activeInDb.isPresent()) {
                String dsl = activeInDb.get().getDslText();
                applySchema(dsl);
                log.info("Loaded active Zanzibar schema v{} from database", activeInDb.get().getVersion());
                return;
            }
        } catch (Exception e) {
            log.warn("DB schema table not ready yet, falling back to classpath:schema.doro ({})", e.getMessage());
        }

        // 기본 schema.doro 리소스 로드
        resetToDefault();
    }

    public void resetToDefault() {
        try {
            Resource resource = resourceLoader.getResource("classpath:schema.doro");
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    String dsl = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    SchemaAst ast = dslParser.parse(dsl);
                    activeSchemaRef.set(ast);
                    activeDslTextRef.set(dsl);
                    log.info("Initialized default Zanzibar schema from classpath:schema.doro");
                }
            }
        } catch (Exception e) {
            log.warn("Could not load default schema.doro: {}", e.getMessage());
        }
    }

    public SchemaAst getActiveSchema() {
        return activeSchemaRef.get();
    }

    public String getActiveDslText() {
        return activeDslTextRef.get();
    }

    @Transactional
    public SchemaDefinition registerSchema(String dslText) {
        SchemaAst ast = dslParser.parse(dslText);

        int nextVersion = schemaRepository.findMaxVersion() + 1;
        schemaRepository.findTopByIsActiveTrueOrderByVersionDesc()
                .ifPresent(SchemaDefinition::deactivate);

        SchemaDefinition newSchema = SchemaDefinition.builder()
                .version(nextVersion)
                .dslText(dslText)
                .isActive(true)
                .build();
        SchemaDefinition saved = schemaRepository.save(newSchema);

        // 메모리 스키마 교체는 커밋 이후에 한다. 롤백된 트랜잭션이 메모리 상태만 바꿔 버리는 것을 막는다.
        runAfterCommit(() -> {
            applySchema(dslText);
            eventPublisher.publishEvent(new SchemaChangedEvent());
            log.info("Activated Zanzibar schema version {} in memory", nextVersion);
        });
        log.info("Registered Zanzibar schema version {}", nextVersion);
        return saved;
    }

    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    public void applySchema(String dslText) {
        SchemaAst ast = dslParser.parse(dslText);
        activeSchemaRef.set(ast);
        activeDslTextRef.set(dslText);
    }
}

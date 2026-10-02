package com.hunnit_beasts.guard.core.dsl.service;

import com.hunnit_beasts.guard.common.exception.ErrorCode;
import com.hunnit_beasts.guard.common.exception.GuardException;
import com.hunnit_beasts.guard.common.validation.ValidationMode;
import com.hunnit_beasts.guard.config.RateLimitedWarn;
import com.hunnit_beasts.guard.core.dsl.ast.AstNodes.SchemaAst;
import com.hunnit_beasts.guard.core.dsl.parser.DslParser;
import com.hunnit_beasts.guard.core.dsl.parser.DslParser.Diagnostic;
import com.hunnit_beasts.guard.core.dsl.parser.DslParser.ParseResult;
import com.hunnit_beasts.guard.domain.schema.entity.SchemaDefinition;
import com.hunnit_beasts.guard.domain.schema.repository.SchemaDefinitionRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SchemaService {

    /** UNIQUE(version) 경합 시 등록을 다시 시도하는 최대 횟수 */
    static final int MAX_REGISTER_ATTEMPTS = 3;
    /** 클래스패스 기본 스키마(DB 버전 아님)를 나타내는 버전. DB 버전은 항상 1 이상이다. */
    static final int CLASSPATH_VERSION = 0;

    private final DslParser dslParser;
    private final SchemaDefinitionRepository schemaRepository;
    private final ResourceLoader resourceLoader;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final ValidationMode validationMode;
    private final long refreshIntervalSeconds;

    private final AtomicReference<SchemaAst> activeSchemaRef = new AtomicReference<>();
    private final AtomicReference<String> activeDslTextRef = new AtomicReference<>();
    /** 메모리에 적용된 스키마의 DB 버전. 갱신 스레드와 등록 커밋 콜백이 같은 락으로 읽고 쓴다. */
    private int activeVersion = CLASSPATH_VERSION;
    private final Object versionLock = new Object();

    private final RateLimitedWarn rateLimitedWarn = new RateLimitedWarn();
    private ScheduledExecutorService refresher;

    public SchemaService(DslParser dslParser,
                         SchemaDefinitionRepository schemaRepository,
                         ResourceLoader resourceLoader,
                         ApplicationEventPublisher eventPublisher,
                         PlatformTransactionManager transactionManager,
                         @Value("${doro.guard.validation.mode:WARN}") ValidationMode validationMode,
                         @Value("${doro.guard.schema.refresh-interval-seconds:30}") long refreshIntervalSeconds) {
        this.dslParser = dslParser;
        this.schemaRepository = schemaRepository;
        this.resourceLoader = resourceLoader;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.validationMode = validationMode;
        this.refreshIntervalSeconds = refreshIntervalSeconds;
    }

    @PostConstruct
    public void init() {
        try {
            Optional<SchemaDefinition> activeInDb = schemaRepository.findTopByIsActiveTrueOrderByVersionDesc();

            if (activeInDb.isPresent()) {
                applyVersioned(activeInDb.get().getVersion(), activeInDb.get().getDslText());
                log.info("Loaded active Zanzibar schema v{} from database", activeInDb.get().getVersion());
                startRefresher();
                return;
            }
        } catch (Exception e) {
            // 기동 시점 DB 오류로 기본 스키마로 폴백하면 커스텀 스키마(IAM/블로그 등)의 권한 판정이 달라진다. 반드시 눈에 띄게 남기고,
            // 이후 주기적 갱신(refreshFromDatabase)이 DB 의 실제 활성 스키마로 복구한다.
            log.error("Failed to load active schema from DB; falling back to classpath:schema.doro until the next refresh", e);
        }

        // 기본 schema.doro 리소스 로드
        resetToDefault();
        startRefresher();
    }

    private void startRefresher() {
        if (refreshIntervalSeconds <= 0 || refresher != null) {
            log.info("Schema refresh disabled (interval={}s)", refreshIntervalSeconds);
            return;
        }
        refresher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "guard-schema-refresh");
            t.setDaemon(true);
            return t;
        });
        refresher.scheduleWithFixedDelay(this::refreshQuietly, refreshIntervalSeconds, refreshIntervalSeconds, TimeUnit.SECONDS);
        log.info("Schema refresh scheduled every {}s", refreshIntervalSeconds);
    }

    @PreDestroy
    void stopRefresher() {
        if (refresher != null) {
            refresher.shutdownNow();
        }
    }

    /** 스케줄러 스레드는 예외로 멈추면 안 되므로 여기서 모두 잡아 로그로 남긴다. */
    private void refreshQuietly() {
        try {
            refreshFromDatabase();
        } catch (Exception e) {
            rateLimitedWarn.warn(log, "schema-refresh", "Schema refresh from DB failed; keeping the in-memory schema ({})",
                    e.toString());
        }
    }

    /**
     * DB 의 활성 스키마 버전이 메모리와 다르면 적용하고 캐시 무효화 이벤트를 발행한다.
     * 다른 인스턴스가 등록한 스키마 반영과, 기동 시 DB 오류로 폴백한 상태의 복구를 겸한다.
     *
     * @return 메모리 스키마를 교체했으면 true
     */
    public boolean refreshFromDatabase() {
        Optional<SchemaDefinition> activeInDb = schemaRepository.findTopByIsActiveTrueOrderByVersionDesc();
        if (activeInDb.isEmpty()) {
            return false;
        }
        int dbVersion = activeInDb.get().getVersion();
        synchronized (versionLock) {
            // 버전은 항상 max+1 로만 늘어난다. 이 스레드가 DB 를 읽는 동안 다른 경로(등록 커밋 콜백)가 더 새 버전을 이미
            // 메모리에 반영했을 수 있으므로, 낡은 읽기가 새 스키마(=권한 규칙)를 되돌리지 않도록 더 높은 버전만 적용한다.
            if (dbVersion < activeVersion) {
                rateLimitedWarn.warn(log, "schema-refresh-older",
                        "DB active schema v{} is older than the in-memory v{}; keeping the in-memory schema", dbVersion, activeVersion);
                return false;
            }
            if (dbVersion == activeVersion) {
                return false;
            }
            applyVersioned(dbVersion, activeInDb.get().getDslText());
        }
        eventPublisher.publishEvent(new SchemaChangedEvent());
        log.info("Refreshed Zanzibar schema from database: now v{}", dbVersion);
        return true;
    }

    public int getActiveVersion() {
        synchronized (versionLock) {
            return activeVersion;
        }
    }

    public void resetToDefault() {
        try {
            Resource resource = resourceLoader.getResource("classpath:schema.doro");
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    String dsl = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    applyVersioned(CLASSPATH_VERSION, dsl);
                    log.info("Initialized default Zanzibar schema from classpath:schema.doro");
                }
            }
        } catch (Exception e) {
            log.error("Could not load default schema.doro", e);
        }
    }

    public SchemaAst getActiveSchema() {
        return activeSchemaRef.get();
    }

    public String getActiveDslText() {
        return activeDslTextRef.get();
    }

    /**
     * 새 버전을 등록하고 활성화한다. 동시 등록으로 UNIQUE(version)가 충돌하면 새 트랜잭션에서 다시 시도하고,
     * 계속 실패하면 SCHEMA_CONFLICT(409)로 알린다. 메모리 반영과 이벤트 발행은 커밋 이후에 한다.
     */
    public SchemaDefinition registerSchema(String dslText) {
        ParseResult parsed = dslParser.parseWithDiagnostics(dslText);
        enforceDiagnostics(parsed.diagnostics());

        for (int attempt = 1; attempt <= MAX_REGISTER_ATTEMPTS; attempt++) {
            try {
                return transactionTemplate.execute(status -> saveNewVersion(dslText));
            } catch (DataIntegrityViolationException e) {
                log.warn("Schema version conflict on register (attempt {}/{})", attempt, MAX_REGISTER_ATTEMPTS);
                if (attempt == MAX_REGISTER_ATTEMPTS) {
                    log.error("Schema registration failed after {} attempts due to version conflicts", MAX_REGISTER_ATTEMPTS, e);
                    throw new GuardException(ErrorCode.SCHEMA_CONFLICT);
                }
            }
        }
        throw new GuardException(ErrorCode.SCHEMA_CONFLICT);
    }

    private SchemaDefinition saveNewVersion(String dslText) {
        int nextVersion = schemaRepository.findMaxVersion() + 1;
        schemaRepository.findTopByIsActiveTrueOrderByVersionDesc()
                .ifPresent(SchemaDefinition::deactivate);

        SchemaDefinition newSchema = SchemaDefinition.builder()
                .version(nextVersion)
                .dslText(dslText)
                .isActive(true)
                .build();
        // 유니크 위반이 이 트랜잭션 안에서 드러나도록 즉시 flush 한다.
        SchemaDefinition saved = schemaRepository.saveAndFlush(newSchema);

        // 메모리 스키마 교체는 커밋 이후에 한다. 롤백된 트랜잭션이 메모리 상태만 바꿔 버리는 것을 막는다.
        runAfterCommit(() -> {
            synchronized (versionLock) {
                applyVersioned(nextVersion, dslText);
            }
            eventPublisher.publishEvent(new SchemaChangedEvent());
            log.info("Activated Zanzibar schema version {} in memory", nextVersion);
        });
        log.info("Registered Zanzibar schema version {}", nextVersion);
        return saved;
    }

    /** 검증 모드에 따라 진단을 무시(OFF)/로그(WARN)/거부(ENFORCE)한다. */
    private void enforceDiagnostics(List<Diagnostic> diagnostics) {
        if (diagnostics.isEmpty() || validationMode == ValidationMode.OFF) {
            return;
        }
        String summary = diagnostics.stream().map(Diagnostic::toString).collect(Collectors.joining("; "));
        if (validationMode == ValidationMode.ENFORCE) {
            throw new GuardException(ErrorCode.INVALID_SYNTAX, "스키마 검증 실패: " + summary);
        }
        log.warn("Schema registered with {} validation warning(s): {}", diagnostics.size(), summary);
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

    /** 저장된/기본 스키마를 읽는 경로. 검증 진단 없이 관대하게 파싱한다. */
    public void applySchema(String dslText) {
        SchemaAst ast = dslParser.parse(dslText);
        activeSchemaRef.set(ast);
        activeDslTextRef.set(dslText);
    }

    private void applyVersioned(int version, String dslText) {
        synchronized (versionLock) {
            applySchema(dslText);
            activeVersion = version;
        }
    }
}

package com.hunnit_beasts.guard.config;

import com.hunnit_beasts.guard.common.exception.ErrorCode;
import com.hunnit_beasts.guard.common.exception.GuardException;
import com.hunnit_beasts.guard.core.dsl.ast.AstNodes.SchemaAst;
import com.hunnit_beasts.guard.core.dsl.parser.DslParser;
import com.hunnit_beasts.guard.core.dsl.service.SchemaService;
import com.hunnit_beasts.guard.domain.tuple.dto.TupleDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 네임스페이스가 지정된 호출자({@code blog:토큰::blog_*} 형식)가 자기 네임스페이스 밖의 권한 데이터를 바꾸지 못하게 한다.
 * 튜플 쓰기/삭제는 객체 네임스페이스로, 스키마 교체는 소유하지 않은 타입이 그대로인지로 판단한다.
 * 검사 모드는 서비스 토큰 모드를 따른다: WARN 은 로그만 남기고 통과, ENFORCE 는 403(gRPC PERMISSION_DENIED).
 * 응답에는 입력값을 되돌려 주지 않고, 어떤 네임스페이스였는지는 로그에만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallerNamespaceGuard {

    /** 로그에 남기는 위반 네임스페이스 수 상한. 거대한 배치가 로그를 부풀리지 못하게 한다. */
    private static final int LOGGED_VIOLATIONS = 5;

    private final ServiceAuthProperties properties;
    private final SchemaService schemaService;
    private final DslParser dslParser;
    private final RateLimitedWarn rateLimitedWarn = new RateLimitedWarn();

    /** 튜플 쓰기/삭제 배치의 모든 객체 네임스페이스가 호출자 소유인지. 하나라도 아니면 배치 전체를 거부한다. */
    public void requireTupleAccess(String caller, String operation, Collection<TupleDto> tuples) {
        if (!restricted(caller)) {
            return;
        }
        Set<String> violations = new LinkedHashSet<>();
        for (TupleDto tuple : tuples) {
            if (!properties.ownsNamespace(caller, tuple.namespace())) {
                violations.add(tuple.namespace());
            }
        }
        reject(caller, operation, violations);
    }

    /**
     * 새 스키마가 호출자 소유가 아닌 타입을 바꾸거나 지우거나 만들지 않는지. 서비스는 활성 스키마에 자기 타입을 합쳐 등록하므로
     * 다른 타입은 현재 활성 스키마와 똑같아야 한다.
     */
    public void requireSchemaChange(String caller, String dslText) {
        if (!restricted(caller)) {
            return;
        }
        SchemaAst active = schemaService.getActiveSchema();
        SchemaAst proposed = dslParser.parse(dslText);
        Set<String> names = new LinkedHashSet<>(active.types().keySet());
        names.addAll(proposed.types().keySet());
        Set<String> violations = new LinkedHashSet<>();
        for (String name : names) {
            if (!properties.ownsNamespace(caller, name) && !java.util.Objects.equals(active.getType(name), proposed.getType(name))) {
                violations.add(name);
            }
        }
        reject(caller, "schema", violations);
    }

    private boolean restricted(String caller) {
        return properties.isActive() && properties.isNamespaceRestricted(caller);
    }

    private void reject(String caller, String operation, Set<String> violations) {
        if (violations.isEmpty()) {
            return;
        }
        rateLimitedWarn.warn(log, "ns:" + caller + ":" + operation,
                "Guard {} outside the caller's namespaces: caller={}, namespaces={}{}, mode={}", operation, caller,
                violations.stream().limit(LOGGED_VIOLATIONS).toList(), violations.size() > LOGGED_VIOLATIONS ? "…" : "",
                properties.getMode());
        if (properties.getMode() == ServiceAuthProperties.Mode.ENFORCE) {
            throw new GuardException(ErrorCode.NAMESPACE_FORBIDDEN);
        }
    }
}

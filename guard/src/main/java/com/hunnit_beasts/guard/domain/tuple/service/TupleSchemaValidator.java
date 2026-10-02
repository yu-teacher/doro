package com.hunnit_beasts.guard.domain.tuple.service;

import com.hunnit_beasts.guard.common.exception.ErrorCode;
import com.hunnit_beasts.guard.common.exception.GuardException;
import com.hunnit_beasts.guard.common.validation.ValidationMode;
import com.hunnit_beasts.guard.config.RateLimitedWarn;
import com.hunnit_beasts.guard.core.dsl.ast.AstNodes.SchemaAst;
import com.hunnit_beasts.guard.core.dsl.ast.AstNodes.TypeAst;
import com.hunnit_beasts.guard.core.dsl.service.SchemaService;
import com.hunnit_beasts.guard.domain.tuple.dto.TupleDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 튜플이 활성 스키마에 선언된 타입/릴레이션을 쓰는지 검사한다. 직접 튜플의 평가 의미는 바꾸지 않는다.
 * 활성 스키마가 없으면 검사하지 않는다. 모드: OFF 무시 / WARN 문제별 속도제한 로그 / ENFORCE 배치 전체 거부(400).
 */
@Slf4j
@Component
public class TupleSchemaValidator {

    private final SchemaService schemaService;
    private final ValidationMode mode;
    private final RateLimitedWarn rateLimitedWarn = new RateLimitedWarn();

    public TupleSchemaValidator(SchemaService schemaService,
                                @Value("${doro.guard.validation.mode:WARN}") ValidationMode mode) {
        this.schemaService = schemaService;
        this.mode = mode;
    }

    /** 배치를 검사해 모드에 따라 로그를 남기거나 INVALID_TUPLE 로 거부한다. */
    public void validate(Collection<TupleDto> tuples) {
        if (mode == ValidationMode.OFF) {
            return;
        }
        SchemaAst schema = schemaService.getActiveSchema();
        if (schema == null || schema.types().isEmpty()) {
            return;
        }

        Set<String> problems = new LinkedHashSet<>();
        for (TupleDto t : tuples) {
            TypeAst type = schema.getType(t.namespace());
            if (type == null) {
                problems.add("타입 '" + t.namespace() + "' 이(가) 스키마에 선언되지 않았습니다");
            } else if (!type.relations().containsKey(t.relation())) {
                problems.add("타입 '" + t.namespace() + "' 에 relation '" + t.relation() + "' 이(가) 선언되지 않았습니다");
            }

            String subjectRelation = t.subjectRelation();
            if (subjectRelation != null && !subjectRelation.isBlank()) {
                TypeAst subjectType = schema.getType(t.subjectNamespace());
                if (subjectType == null) {
                    problems.add("subject 타입 '" + t.subjectNamespace() + "' 이(가) 스키마에 선언되지 않았습니다");
                } else if (!subjectType.relations().containsKey(subjectRelation)) {
                    problems.add("subject 타입 '" + t.subjectNamespace() + "' 에 relation '" + subjectRelation
                            + "' 이(가) 선언되지 않았습니다");
                }
            }
        }
        if (problems.isEmpty()) {
            return;
        }

        if (mode == ValidationMode.ENFORCE) {
            throw new GuardException(ErrorCode.INVALID_TUPLE, "튜플 검증 실패: " + String.join("; ", problems));
        }
        // 문제(타입/릴레이션 조합)별로 한 번씩만 기록한다. 값(object id 등)은 키에 넣지 않는다.
        for (String problem : problems) {
            rateLimitedWarn.warn(log, "tuple:" + problem, "Tuple not matching the active schema: {}", problem);
        }
    }
}

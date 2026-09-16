package com.hunnit_beasts.guard.core.engine;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hunnit_beasts.guard.core.dsl.ast.AstNodes.*;
import com.hunnit_beasts.guard.core.dsl.service.SchemaService;
import com.hunnit_beasts.guard.domain.tuple.entity.RelationTuple;
import com.hunnit_beasts.guard.domain.tuple.repository.RelationTupleRepository;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CheckEngine {

    private final RelationTupleRepository tupleRepository;
    private final SchemaService schemaService;

    @Value("${doro.guard.engine.max-depth:32}")
    private int maxDepth = 32;

    // L1 인메모리 캐시 (TTL 60초)
    private final Cache<String, Boolean> l1Cache = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterWrite(Duration.ofSeconds(60))
            .build();

    public record CheckResult(boolean allowed, int maxDepthReached, String reason) {}

    @Getter
    @Builder
    public static class CheckContext {
        private final String namespace;
        private final String objectId;
        private final String relation;
        private final String subjectNamespace;
        private final String subjectId;
        private final String subjectRelation;
        private final int depth;
        private final Set<String> visited;

        public String toSignature() {
            String sub = subjectNamespace + ":" + subjectId;
            if (subjectRelation != null && !subjectRelation.isBlank()) {
                sub += "#" + subjectRelation;
            }
            return namespace + ":" + objectId + "#" + relation + "@" + sub;
        }

        public CheckContext nextDepth(String newNs, String newObjId, String newRel,
                                      String newSubNs, String newSubId, String newSubRel) {
            Set<String> newVisited = new HashSet<>(visited);
            newVisited.add(toSignature());
            return CheckContext.builder()
                    .namespace(newNs)
                    .objectId(newObjId)
                    .relation(newRel)
                    .subjectNamespace(newSubNs)
                    .subjectId(newSubId)
                    .subjectRelation(newSubRel)
                    .depth(depth + 1)
                    .visited(newVisited)
                    .build();
        }
    }

    public CheckResult check(String namespace, String objectId, String relation,
                             String subjectNamespace, String subjectId, String subjectRelation) {
        CheckContext initialContext = CheckContext.builder()
                .namespace(namespace)
                .objectId(objectId)
                .relation(relation)
                .subjectNamespace(subjectNamespace)
                .subjectId(subjectId)
                .subjectRelation(subjectRelation)
                .depth(0)
                .visited(new HashSet<>())
                .build();

        String cacheKey = initialContext.toSignature();
        Boolean cached = l1Cache.getIfPresent(cacheKey);
        if (cached != null) {
            return new CheckResult(cached, 0, "L1_CACHE_HIT");
        }

        boolean allowed = evaluate(initialContext);
        l1Cache.put(cacheKey, allowed);
        return new CheckResult(allowed, initialContext.getDepth(), allowed ? "ACCESS_GRANTED" : "ACCESS_DENIED");
    }

    private boolean evaluate(CheckContext ctx) {
        // 1. 최대 깊이 초과 및 순환 참조 방어 (Cycle Detection)
        if (ctx.getDepth() > maxDepth) {
            log.warn("CheckEngine: Max recursion depth reached ({}) for {}", maxDepth, ctx.toSignature());
            return false;
        }

        if (ctx.getVisited().contains(ctx.toSignature())) {
            log.warn("CheckEngine: Circular reference detected at {}", ctx.toSignature());
            return false; // 순환 발생 시 안전하게 false 반환
        }

        // 2. 직접 튜플 매칭 (Direct Tuple Check)
        if (ctx.getSubjectRelation() == null || ctx.getSubjectRelation().isBlank()) {
            if (tupleRepository.existsDirectTuple(ctx.getNamespace(), ctx.getObjectId(), ctx.getRelation(),
                    ctx.getSubjectNamespace(), ctx.getSubjectId())) {
                return true;
            }
        } else {
            if (tupleRepository.existsUsersetTuple(ctx.getNamespace(), ctx.getObjectId(), ctx.getRelation(),
                    ctx.getSubjectNamespace(), ctx.getSubjectId(), ctx.getSubjectRelation())) {
                return true;
            }
        }

        // 3. 중첩 그룹/Userset 튜플 역추적 (예: doc:1#viewer@group:eng#member)
        List<RelationTuple> relationTuples = tupleRepository.findByObjectAndRelation(
                ctx.getNamespace(), ctx.getObjectId(), ctx.getRelation());

        for (RelationTuple tuple : relationTuples) {
            if (tuple.getSubjectRelation() != null && !tuple.getSubjectRelation().isBlank()) {
                // 대상이 그룹 등의 Userset인 경우, 요청자가 그 그룹의 멤버인지 재귀 검사
                CheckContext nestedCtx = ctx.nextDepth(
                        tuple.getSubjectNamespace(),
                        tuple.getSubjectId(),
                        tuple.getSubjectRelation(),
                        ctx.getSubjectNamespace(),
                        ctx.getSubjectId(),
                        null
                );
                if (evaluate(nestedCtx)) {
                    return true;
                }
            }
        }

        // 4. 스키마 DSL AST 기반 Userset Rewrite & TTU 평가
        SchemaAst schema = schemaService.getActiveSchema();
        if (schema == null) {
            return false;
        }

        TypeAst typeAst = schema.getType(ctx.getNamespace());
        if (typeAst == null) {
            return false;
        }

        RelationAst relationAst = typeAst.relations().get(ctx.getRelation());
        if (relationAst == null) {
            return false;
        }

        return evaluateExpression(relationAst.expression(), ctx);
    }

    private boolean evaluateExpression(ExpressionNode node, CheckContext ctx) {
        if (node instanceof DirectRelationNode directNode) {
            // Direct type match check
            return false; // 이미 2단계에서 직접 매칭했으므로
        }

        if (node instanceof ComputedUsersetNode computedNode) {
            // 동일 객체의 다른 릴레이션 평가 (예: viewer 규칙이 editor 호출)
            CheckContext nextCtx = ctx.nextDepth(
                    ctx.getNamespace(),
                    ctx.getObjectId(),
                    computedNode.relationName(),
                    ctx.getSubjectNamespace(),
                    ctx.getSubjectId(),
                    ctx.getSubjectRelation()
            );
            return evaluate(nextCtx);
        }

        if (node instanceof UnionNode unionNode) {
            // 합집합 (|): 하나라도 만족하면 true
            for (ExpressionNode child : unionNode.children()) {
                if (evaluateExpression(child, ctx)) {
                    return true;
                }
            }
            return false;
        }

        if (node instanceof IntersectionNode intersectionNode) {
            // 교집합 (&): 모든 자식이 만족해야 true
            for (ExpressionNode child : intersectionNode.children()) {
                if (!evaluateExpression(child, ctx)) {
                    return false;
                }
            }
            return true;
        }

        if (node instanceof DifferenceNode diffNode) {
            // 차집합 (-): base는 만족하고 subtract는 불만족해야 true
            return evaluateExpression(diffNode.base(), ctx) && !evaluateExpression(diffNode.subtract(), ctx);
        }

        if (node instanceof TupleToUsersetNode ttuNode) {
            // TTU: parent#viewer -> 현재 객체의 parent 튜플을 조회하여 부모 객체들에 대해 viewer 권한 재귀 검사
            List<RelationTuple> parentTuples = tupleRepository.findByObjectAndRelation(
                    ctx.getNamespace(), ctx.getObjectId(), ttuNode.tuplesetRelation());

            for (RelationTuple parentTuple : parentTuples) {
                CheckContext parentCtx = ctx.nextDepth(
                        parentTuple.getSubjectNamespace(),
                        parentTuple.getSubjectId(),
                        ttuNode.computedUsersetRelation(),
                        ctx.getSubjectNamespace(),
                        ctx.getSubjectId(),
                        ctx.getSubjectRelation()
                );
                if (evaluate(parentCtx)) {
                    return true;
                }
            }
            return false;
        }

        if (node instanceof SubjectUsersetNode subjectUsersetNode) {
            // Subject Userset (예: group#member)
            return false; // 3단계에서 역추적 처리됨
        }

        return false;
    }

    public void invalidateCache() {
        l1Cache.invalidateAll();
    }
}

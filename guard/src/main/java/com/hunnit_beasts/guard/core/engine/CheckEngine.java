package com.hunnit_beasts.guard.core.engine;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hunnit_beasts.guard.core.dsl.ast.AstNodes.*;
import com.hunnit_beasts.guard.core.dsl.service.SchemaChangedEvent;
import com.hunnit_beasts.guard.core.dsl.service.SchemaService;
import com.hunnit_beasts.guard.domain.tuple.entity.RelationTuple;
import com.hunnit_beasts.guard.domain.tuple.repository.RelationTupleRepository;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class CheckEngine {

    private final RelationTupleRepository tupleRepository;
    private final SchemaService schemaService;

    @Value("${doro.guard.engine.max-depth:32}")
    private int maxDepth = 32;

    @Value("${doro.guard.engine.cache-ttl-seconds:60}")
    private long cacheTtlSeconds = 60;

    @Value("${doro.guard.engine.cache-max-size:50000}")
    private long cacheMaxSize = 50_000;

    // 캐시 무효화 세대. 평가 도중 무효화가 일어났다면 그 평가 결과는 캐시에 넣지 않는다.
    private final AtomicLong cacheGeneration = new AtomicLong();

    /** L1 캐시 값: 판정과 그 판정에 도달한 최대 재귀 깊이 */
    private record CachedDecision(boolean allowed, int depth) {}

    // L1 인메모리 캐시. TTL/최대 크기는 doro.guard.engine.cache-* 로 설정한다.
    private Cache<String, CachedDecision> l1Cache = buildCache();

    @PostConstruct
    void initCache() {
        l1Cache = buildCache();
        log.info("CheckEngine L1 cache: ttl={}s, maxSize={}", cacheTtlSeconds, cacheMaxSize);
    }

    /** TTL 또는 최대 크기가 0 이하이면 캐시를 사용하지 않는다. */
    private boolean cacheEnabled() {
        return cacheTtlSeconds > 0 && cacheMaxSize > 0;
    }

    private Cache<String, CachedDecision> buildCache() {
        return Caffeine.newBuilder()
                .maximumSize(Math.max(0, cacheMaxSize))
                .expireAfterWrite(Duration.ofSeconds(Math.max(0, cacheTtlSeconds)))
                .build();
    }

    /** 깊이 초과/순환으로 탐색이 잘린 횟수. 잘린 결과는 "진짜 거부"와 구분되므로 캐시하지 않고 차집합에서는 거부로 처리한다. */
    public static class EvalState {
        private int cutoffs;
        private int maxDepthReached;

        void markCutoff() {
            cutoffs++;
        }

        int cutoffs() {
            return cutoffs;
        }

        void observeDepth(int depth) {
            if (depth > maxDepthReached) {
                maxDepthReached = depth;
            }
        }

        int maxDepthReached() {
            return maxDepthReached;
        }
    }

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
        // 한 번의 check 요청 전체에서 공유되는 평가 상태
        private final EvalState state;

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
                    .state(state)
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
                .state(new EvalState())
                .build();

        String cacheKey = initialContext.toSignature();
        CachedDecision cached = l1Cache.getIfPresent(cacheKey);
        if (cached != null) {
            return new CheckResult(cached.allowed(), cached.depth(), "L1_CACHE_HIT");
        }

        long generation = cacheGeneration.get();
        boolean allowed = evaluate(initialContext);
        // 루트 컨텍스트의 depth 는 항상 0 이므로, 요청 전체에서 실제로 도달한 최대 깊이를 EvalState 에서 읽는다.
        int depthReached = initialContext.getState().maxDepthReached();
        boolean cacheable = cacheEnabled() && initialContext.getState().cutoffs() == 0 && generation == cacheGeneration.get();
        if (cacheable) {
            l1Cache.put(cacheKey, new CachedDecision(allowed, depthReached));
        }
        return new CheckResult(allowed, depthReached, allowed ? "ACCESS_GRANTED" : "ACCESS_DENIED");
    }

    private boolean evaluate(CheckContext ctx) {
        // 1. 최대 깊이 초과 및 순환 참조 방어 (Cycle Detection)
        if (ctx.getDepth() > maxDepth) {
            log.warn("CheckEngine: Max recursion depth reached ({}) for {}", maxDepth, ctx.toSignature());
            ctx.getState().markCutoff();
            return false;
        }

        ctx.getState().observeDepth(ctx.getDepth());

        if (ctx.getVisited().contains(ctx.toSignature())) {
            log.warn("CheckEngine: Circular reference detected at {}", ctx.toSignature());
            ctx.getState().markCutoff();
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
            if (!evaluateExpression(diffNode.base(), ctx)) {
                return false;
            }
            int cutoffsBefore = ctx.getState().cutoffs();
            boolean subtracted = evaluateExpression(diffNode.subtract(), ctx);
            if (ctx.getState().cutoffs() > cutoffsBefore) {
                // 제외 대상(subtract) 평가가 잘렸다면 결과를 신뢰할 수 없으므로 fail-closed 로 거부한다.
                log.warn("CheckEngine: subtract branch truncated at {}; denying (fail-closed)", ctx.toSignature());
                return false;
            }
            return !subtracted;
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
        cacheGeneration.incrementAndGet();
        l1Cache.invalidateAll();
    }

    @EventListener
    public void onSchemaChanged(SchemaChangedEvent event) {
        invalidateCache();
    }
}

package com.hunnit_beasts.guard.core.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunnit_beasts.guard.common.exception.ErrorCode;
import com.hunnit_beasts.guard.common.exception.GuardException;
import com.hunnit_beasts.guard.core.dsl.ast.AstNodes.*;
import com.hunnit_beasts.guard.core.dsl.service.SchemaService;
import com.hunnit_beasts.guard.domain.tuple.entity.RelationTuple;
import com.hunnit_beasts.guard.domain.tuple.repository.RelationTupleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 객체#릴레이션에 접근할 수 있는 주체 트리를 JSON 으로 전개한다 (Zanzibar Expand).
 * CheckEngine 과 같은 규칙을 따른다: 직접 튜플 먼저, userset 주체(subject_relation 이 있는 튜플)는 자식으로 전개,
 * 이어서 스키마 표현식(합/교/차, 같은 객체의 다른 릴레이션, TTU). 최대 깊이(max-depth), 경로별 순환 방지,
 * 총 노드 수 상한(expand-max-nodes)을 지키며, 상한/깊이에 걸려 잘리면 루트에 "truncated":true 를 표시한다.
 *
 * <p>노드: {"object":"ns:id#rel","type":"leaf|union|intersection|difference|computed|ttu","subjects":[...],"children":[...]}.
 * 직접 튜플은 표현식 종류와 관계없이 먼저 합쳐지므로 subjects 에는 항상 직접 튜플 주체가 들어간다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpandEngine {

    private static final String TYPE_LEAF = "leaf";
    private static final String TYPE_UNION = "union";
    private static final String TYPE_INTERSECTION = "intersection";
    private static final String TYPE_DIFFERENCE = "difference";
    private static final String TYPE_COMPUTED = "computed";
    private static final String TYPE_TTU = "ttu";

    private final RelationTupleRepository tupleRepository;
    private final SchemaService schemaService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${doro.guard.engine.max-depth:32}")
    private int maxDepth = 32;

    @Value("${doro.guard.engine.expand-max-nodes:5000}")
    private int maxNodes = 5000;

    /** 한 번의 expand 요청 동안의 상태 */
    private static final class Run {
        private int nodes;
        private boolean truncated;
        private final Set<String> path = new HashSet<>();
    }

    public String expand(String namespace, String objectId, String relation) {
        Run run = new Run();
        SchemaAst schema = schemaService.getActiveSchema();
        Map<String, Object> root = expandRelation(namespace, objectId, relation, 0, run, schema);
        if (root == null) {
            // 노드 상한이 0 이하인 설정에서만 가능하다. 빈 루트라도 돌려준다.
            root = newNode(label(namespace, objectId, relation), TYPE_LEAF);
        }
        if (run.truncated) {
            root.put("truncated", true);
            log.warn("Expand truncated at {} (nodes={}, maxNodes={}, maxDepth={})",
                    label(namespace, objectId, relation), run.nodes, maxNodes, maxDepth);
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new GuardException(ErrorCode.INTERNAL_SERVER_ERROR, "Expand 결과를 직렬화하지 못했습니다.");
        }
    }

    /** 한 객체#릴레이션 노드를 만든다. 잘리거나 순환이면 null. */
    private Map<String, Object> expandRelation(String ns, String id, String rel, int depth, Run run, SchemaAst schema) {
        if (depth > maxDepth) {
            run.truncated = true;
            return null;
        }
        String label = label(ns, id, rel);
        if (!run.path.add(label)) {
            // 이 경로에서 이미 전개 중인 노드 = 순환. 다시 전개하지 않는다.
            log.debug("Expand: cycle skipped at {}", label);
            return null;
        }
        try {
            Map<String, Object> node = newCountedNode(label, TYPE_LEAF, run);
            if (node == null) {
                return null;
            }

            List<RelationTuple> tuples = new ArrayList<>(tupleRepository.findByObjectAndRelation(ns, id, rel));
            tuples.sort(java.util.Comparator.comparing(RelationTuple::getSubjectNamespace)
                    .thenComparing(RelationTuple::getSubjectId)
                    .thenComparing(t -> t.getSubjectRelation() == null ? "" : t.getSubjectRelation()));

            List<String> subjects = new ArrayList<>();
            List<Map<String, Object>> children = new ArrayList<>();
            for (RelationTuple tuple : tuples) {
                String subject = tuple.getSubjectNamespace() + ":" + tuple.getSubjectId();
                boolean userset = tuple.getSubjectRelation() != null && !tuple.getSubjectRelation().isBlank();
                if (userset) {
                    subject += "#" + tuple.getSubjectRelation();
                }
                subjects.add(subject);
                if (userset && !run.truncated) {
                    addIfPresent(children, expandRelation(tuple.getSubjectNamespace(), tuple.getSubjectId(),
                            tuple.getSubjectRelation(), depth + 1, run, schema));
                }
            }
            node.put("subjects", subjects);

            RelationAst relationAst = relationOf(schema, ns, rel);
            if (relationAst != null) {
                applyExpression(node, children, relationAst.expression(), ns, id, depth, run, schema);
            }
            node.put("children", children);
            return node;
        } finally {
            run.path.remove(label);
        }
    }

    /** 표현식의 모양대로 node 의 type 을 정하고 children 에 하위 노드를 추가한다. */
    private void applyExpression(Map<String, Object> node, List<Map<String, Object>> children, ExpressionNode expr,
                                 String ns, String id, int depth, Run run, SchemaAst schema) {
        if (expr instanceof UnionNode union) {
            node.put("type", TYPE_UNION);
            for (ExpressionNode child : union.children()) {
                addIfPresent(children, term(child, ns, id, depth, run, schema));
            }
        } else if (expr instanceof IntersectionNode intersection) {
            node.put("type", TYPE_INTERSECTION);
            for (ExpressionNode child : intersection.children()) {
                addIfPresent(children, term(child, ns, id, depth, run, schema));
            }
        } else if (expr instanceof DifferenceNode difference) {
            node.put("type", TYPE_DIFFERENCE);
            addIfPresent(children, term(difference.base(), ns, id, depth, run, schema));
            addIfPresent(children, term(difference.subtract(), ns, id, depth, run, schema));
        } else if (expr instanceof ComputedUsersetNode computed) {
            node.put("type", TYPE_COMPUTED);
            addIfPresent(children, expandRelation(ns, id, computed.relationName(), depth + 1, run, schema));
        } else if (expr instanceof TupleToUsersetNode ttu) {
            node.put("type", TYPE_TTU);
            children.addAll(ttuChildren(ttu, ns, id, depth, run, schema));
        }
        // DirectRelationNode / SubjectUsersetNode: 직접 튜플(subjects)과 userset 자식으로 이미 표현된다.
    }

    /** union/intersection/difference 의 항 하나. 직접 항은 표시할 것이 없어 null. */
    private Map<String, Object> term(ExpressionNode expr, String ns, String id, int depth, Run run, SchemaAst schema) {
        if (run.truncated) {
            return null;
        }
        if (expr instanceof ComputedUsersetNode computed) {
            Map<String, Object> wrapper = newCountedNode(label(ns, id, computed.relationName()), TYPE_COMPUTED, run);
            if (wrapper == null) {
                return null;
            }
            wrapper.put("subjects", List.of());
            List<Map<String, Object>> children = new ArrayList<>();
            addIfPresent(children, expandRelation(ns, id, computed.relationName(), depth + 1, run, schema));
            wrapper.put("children", children);
            return wrapper;
        }
        if (expr instanceof TupleToUsersetNode ttu) {
            Map<String, Object> wrapper = newCountedNode(
                    label(ns, id, ttu.tuplesetRelation()) + "->" + ttu.computedUsersetRelation(), TYPE_TTU, run);
            if (wrapper == null) {
                return null;
            }
            List<Map<String, Object>> parents = ttuChildren(ttu, ns, id, depth, run, schema);
            if (parents.isEmpty()) {
                // 기본 스키마의 group#member 처럼 tupleset 튜플이 없는 비활성 TTU 항은 트리에 싣지 않는다.
                run.nodes--;
                return null;
            }
            wrapper.put("subjects", List.of());
            wrapper.put("children", parents);
            return wrapper;
        }
        if (expr instanceof UnionNode || expr instanceof IntersectionNode || expr instanceof DifferenceNode) {
            Map<String, Object> nested = newCountedNode(label(ns, id, "(expr)"), TYPE_LEAF, run);
            if (nested == null) {
                return null;
            }
            nested.put("subjects", List.of());
            List<Map<String, Object>> children = new ArrayList<>();
            applyExpression(nested, children, expr, ns, id, depth, run, schema);
            nested.put("children", children);
            return nested;
        }
        return null;
    }

    /** TTU: 현재 객체의 tupleset 릴레이션 튜플이 가리키는 부모 객체들의 computed 릴레이션을 전개한다. */
    private List<Map<String, Object>> ttuChildren(TupleToUsersetNode ttu, String ns, String id, int depth, Run run, SchemaAst schema) {
        List<RelationTuple> parents = new ArrayList<>(tupleRepository.findByObjectAndRelation(ns, id, ttu.tuplesetRelation()));
        parents.sort(java.util.Comparator.comparing(RelationTuple::getSubjectNamespace)
                .thenComparing(RelationTuple::getSubjectId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (RelationTuple parent : parents) {
            if (run.truncated) {
                break;
            }
            addIfPresent(result, expandRelation(parent.getSubjectNamespace(), parent.getSubjectId(),
                    ttu.computedUsersetRelation(), depth + 1, run, schema));
        }
        return result;
    }

    private static RelationAst relationOf(SchemaAst schema, String ns, String rel) {
        if (schema == null) {
            return null;
        }
        TypeAst type = schema.getType(ns);
        return type == null ? null : type.relations().get(rel);
    }

    /** 노드를 만들며 총 개수를 센다. 상한을 넘으면 null 을 돌려주고 truncated 를 표시한다. */
    private Map<String, Object> newCountedNode(String label, String type, Run run) {
        if (run.nodes >= maxNodes) {
            run.truncated = true;
            return null;
        }
        run.nodes++;
        return newNode(label, type);
    }

    private static Map<String, Object> newNode(String label, String type) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("object", label);
        node.put("type", type);
        return node;
    }

    private static void addIfPresent(List<Map<String, Object>> list, Map<String, Object> node) {
        if (node != null) {
            list.add(node);
        }
    }

    private static String label(String ns, String id, String rel) {
        return ns + ":" + id + "#" + rel;
    }
}

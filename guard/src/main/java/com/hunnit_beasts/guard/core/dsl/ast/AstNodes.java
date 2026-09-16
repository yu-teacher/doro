package com.hunnit_beasts.guard.core.dsl.ast;

import java.util.List;
import java.util.Map;

public class AstNodes {

    public sealed interface ExpressionNode permits
            DirectRelationNode,
            ComputedUsersetNode,
            TupleToUsersetNode,
            SubjectUsersetNode,
            UnionNode,
            IntersectionNode,
            DifferenceNode {
    }

    /**
     * 직접 사용자 또는 특정 타입 대상 (예: user, group)
     */
    public record DirectRelationNode(String targetType) implements ExpressionNode {}

    /**
     * 동일 객체 내 다른 릴레이션 직접 참조 (예: editor 규칙이 owner 참조)
     */
    public record ComputedUsersetNode(String relationName) implements ExpressionNode {}

    /**
     * TTU: Tuple-to-Userset (예: parent#viewer -> parent 튜플의 대상 객체들이 가진 viewer 릴레이션 참조)
     */
    public record TupleToUsersetNode(String tuplesetRelation, String computedUsersetRelation) implements ExpressionNode {}

    /**
     * 대상 객체의 서브셋 릴레이션 (예: group#member)
     */
    public record SubjectUsersetNode(String targetType, String relationName) implements ExpressionNode {}

    /**
     * Userset Rewrite: 합집합 (|)
     */
    public record UnionNode(List<ExpressionNode> children) implements ExpressionNode {}

    /**
     * Userset Rewrite: 교집합 (&)
     */
    public record IntersectionNode(List<ExpressionNode> children) implements ExpressionNode {}

    /**
     * Userset Rewrite: 차집합 (-)
     */
    public record DifferenceNode(ExpressionNode base, ExpressionNode subtract) implements ExpressionNode {}

    /**
     * 개별 릴레이션 정의 (예: relation viewer: user | editor | parent#viewer)
     */
    public record RelationAst(String name, ExpressionNode expression) {}

    /**
     * 네임스페이스/타입 정의 (예: type document { ... })
     */
    public record TypeAst(String name, Map<String, RelationAst> relations) {}

    /**
     * 전체 스키마 AST (모든 타입 정의)
     */
    public record SchemaAst(Map<String, TypeAst> types) {
        public TypeAst getType(String typeName) {
            return types.get(typeName);
        }
    }
}

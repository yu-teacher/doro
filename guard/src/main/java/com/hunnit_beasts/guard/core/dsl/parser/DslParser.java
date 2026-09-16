package com.hunnit_beasts.guard.core.dsl.parser;

import com.hunnit_beasts.guard.common.exception.ErrorCode;
import com.hunnit_beasts.guard.common.exception.GuardException;
import com.hunnit_beasts.guard.core.dsl.ast.AstNodes.*;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class DslParser {

    public SchemaAst parse(String dslContent) {
        if (dslContent == null || dslContent.isBlank()) {
            return new SchemaAst(Collections.emptyMap());
        }

        Map<String, TypeAst> types = new LinkedHashMap<>();
        String[] rawLines = dslContent.split("\\r?\\n");

        String currentTypeName = null;
        Map<String, RelationAst> currentRelations = new LinkedHashMap<>();

        for (String rawLine : rawLines) {
            String line = rawLine.trim();

            // 주석 및 빈 줄 무시
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                continue;
            }

            if (line.startsWith("type ")) {
                if (currentTypeName != null) {
                    types.put(currentTypeName, new TypeAst(currentTypeName, currentRelations));
                    currentRelations = new LinkedHashMap<>();
                }

                // type <name> [{]
                String afterType = line.substring("type ".length()).trim();
                if (afterType.endsWith("{")) {
                    afterType = afterType.substring(0, afterType.length() - 1).trim();
                }
                if (afterType.endsWith("{}")) {
                    afterType = afterType.substring(0, afterType.length() - 2).trim();
                }

                if (afterType.isEmpty()) {
                    throw new GuardException(ErrorCode.INVALID_SYNTAX, "타입 이름이 누락되었습니다: " + rawLine);
                }
                currentTypeName = afterType;

            } else if (line.equals("}")) {
                if (currentTypeName != null) {
                    types.put(currentTypeName, new TypeAst(currentTypeName, currentRelations));
                    currentTypeName = null;
                    currentRelations = new LinkedHashMap<>();
                }
            } else if (line.startsWith("relation ")) {
                if (currentTypeName == null) {
                    throw new GuardException(ErrorCode.INVALID_SYNTAX, "타입 블록 외부에서 릴레이션이 선언되었습니다: " + rawLine);
                }

                // relation <name>: <expression>
                String afterRel = line.substring("relation ".length()).trim();
                int colonIdx = afterRel.indexOf(':');
                if (colonIdx == -1) {
                    throw new GuardException(ErrorCode.INVALID_SYNTAX, "릴레이션 정의에 ':' 기호가 없습니다: " + rawLine);
                }

                String relName = afterRel.substring(0, colonIdx).trim();
                String exprStr = afterRel.substring(colonIdx + 1).trim();

                ExpressionNode exprNode = parseExpression(exprStr, currentRelations);
                currentRelations.put(relName, new RelationAst(relName, exprNode));
            }
        }

        if (currentTypeName != null) {
            types.put(currentTypeName, new TypeAst(currentTypeName, currentRelations));
        }

        return new SchemaAst(types);
    }

    private ExpressionNode parseExpression(String exprStr, Map<String, RelationAst> existingRelations) {
        // 1. 차집합 (-) 연산자 검사
        if (exprStr.contains(" - ")) {
            String[] parts = exprStr.split(" - ", 2);
            return new DifferenceNode(
                    parseExpression(parts[0].trim(), existingRelations),
                    parseExpression(parts[1].trim(), existingRelations)
            );
        }

        // 2. 교집합 (&) 연산자 검사
        if (exprStr.contains(" & ")) {
            String[] parts = exprStr.split(" & ");
            List<ExpressionNode> children = new ArrayList<>();
            for (String p : parts) {
                children.add(parseAtomicExpression(p.trim(), existingRelations));
            }
            return new IntersectionNode(children);
        }

        // 3. 합집합 (|) 연산자 검사 (기본 구분자)
        if (exprStr.contains("|")) {
            String[] parts = exprStr.split("\\|");
            List<ExpressionNode> children = new ArrayList<>();
            for (String p : parts) {
                String token = p.trim();
                if (!token.isEmpty()) {
                    children.add(parseAtomicExpression(token, existingRelations));
                }
            }
            return new UnionNode(children);
        }

        // 4. 단일 원자식
        return parseAtomicExpression(exprStr.trim(), existingRelations);
    }

    private ExpressionNode parseAtomicExpression(String token, Map<String, RelationAst> existingRelations) {
        if (token.contains("#")) {
            String[] parts = token.split("#", 2);
            String prefix = parts[0].trim();
            String suffix = parts[1].trim();

            // 만약 prefix가 현재 타입 내에 이미 선언된 릴레이션이거나 튜플셋 관계라면 TTU (Tuple-To-Userset)
            // 예: parent#viewer -> parent 튜플의 대상이 가진 viewer
            return new TupleToUsersetNode(prefix, suffix);
        }

        // 만약 토큰이 현재 타입 내의 다른 릴레이션 이름과 일치하면 -> ComputedUsersetNode
        if (existingRelations != null && existingRelations.containsKey(token)) {
            return new ComputedUsersetNode(token);
        }

        // 기본 타입/사용자 대상 (예: user, group, owner 등)
        return new DirectRelationNode(token);
    }
}

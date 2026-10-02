package com.hunnit_beasts.guard.core.dsl.parser;

import com.hunnit_beasts.guard.common.exception.ErrorCode;
import com.hunnit_beasts.guard.common.exception.GuardException;
import com.hunnit_beasts.guard.core.dsl.ast.AstNodes.*;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class DslParser {

    /** 파싱은 성공했지만 의심스러운 지점. 평가 방식에는 영향을 주지 않으며 등록 시 검증(validation.mode)에만 쓴다. */
    public record Diagnostic(int line, String message) {
        @Override
        public String toString() {
            return "line " + line + ": " + message;
        }
    }

    private record RelationLine(int line, String typeName, RelationAst relation) {}

    public record ParseResult(SchemaAst ast, List<Diagnostic> diagnostics) {}

    /** 기존 동작 그대로 파싱한다 (진단 없음). 저장된 스키마를 읽는 경로는 항상 이쪽을 써서 기동이 실패하지 않게 한다. */
    public SchemaAst parse(String dslContent) {
        return parseWithDiagnostics(dslContent).ast();
    }

    /**
     * 파싱 결과와 함께 진단을 돌려준다. 진단 대상:
     * (a) 이해하지 못해 무시한 줄, (b) 선언되지 않은 타입 이름을 가리키는 직접 항(전방 참조/오타).
     * TTU 항(예: system#admin)은 검사하지 않는다.
     */
    public ParseResult parseWithDiagnostics(String dslContent) {
        if (dslContent == null || dslContent.isBlank()) {
            return new ParseResult(new SchemaAst(Collections.emptyMap()), List.of());
        }

        List<Diagnostic> diagnostics = new ArrayList<>();
        // 타입 이름 검사를 위해 (줄 번호, 소속 타입, 릴레이션)을 기록해 둔다.
        List<RelationLine> relationLines = new ArrayList<>();

        Map<String, TypeAst> types = new LinkedHashMap<>();
        String[] rawLines = dslContent.split("\\r?\\n");

        String currentTypeName = null;
        Map<String, RelationAst> currentRelations = new LinkedHashMap<>();

        for (int lineIdx = 0; lineIdx < rawLines.length; lineIdx++) {
            String rawLine = rawLines[lineIdx];
            int lineNo = lineIdx + 1;
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
                RelationAst relationAst = new RelationAst(relName, exprNode);
                currentRelations.put(relName, relationAst);
                relationLines.add(new RelationLine(lineNo, currentTypeName, relationAst));
            } else if (!line.equals("{")) {
                // 타입 선언을 다음 줄의 '{' 로 여는 스타일은 무시해도 의미가 같으므로 정상으로 본다.
                diagnostics.add(new Diagnostic(lineNo, "이해할 수 없는 줄이 무시되었습니다: " + line));
            }
        }

        if (currentTypeName != null) {
            types.put(currentTypeName, new TypeAst(currentTypeName, currentRelations));
        }

        for (RelationLine rl : relationLines) {
            collectUndeclaredTypeTerms(rl.relation().expression(), types.keySet(), rl, diagnostics);
        }
        diagnostics.sort(Comparator.comparingInt(Diagnostic::line));

        return new ParseResult(new SchemaAst(types), diagnostics);
    }

    private static void collectUndeclaredTypeTerms(ExpressionNode node, Set<String> typeNames,
                                                   RelationLine rl, List<Diagnostic> diagnostics) {
        if (node instanceof DirectRelationNode direct) {
            if (!typeNames.contains(direct.targetType())) {
                diagnostics.add(new Diagnostic(rl.line(), "type " + rl.typeName() + " 의 relation " + rl.relation().name()
                        + " 이(가) 선언되지 않은 이름 '" + direct.targetType()
                        + "' 을(를) 참조합니다 (오타이거나, 같은 타입에서 나중에 선언된 relation 의 전방 참조일 수 있습니다)."));
            }
        } else if (node instanceof UnionNode union) {
            union.children().forEach(c -> collectUndeclaredTypeTerms(c, typeNames, rl, diagnostics));
        } else if (node instanceof IntersectionNode intersection) {
            intersection.children().forEach(c -> collectUndeclaredTypeTerms(c, typeNames, rl, diagnostics));
        } else if (node instanceof DifferenceNode difference) {
            collectUndeclaredTypeTerms(difference.base(), typeNames, rl, diagnostics);
            collectUndeclaredTypeTerms(difference.subtract(), typeNames, rl, diagnostics);
        }
        // ComputedUsersetNode / TupleToUsersetNode / SubjectUsersetNode 는 검사하지 않는다 (TTU 는 기본 스키마에도 비활성 항이 있다).
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

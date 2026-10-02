package com.hunnit_beasts.guard.core.dsl;

import com.hunnit_beasts.guard.core.dsl.ast.AstNodes.DirectRelationNode;
import com.hunnit_beasts.guard.core.dsl.ast.AstNodes.SchemaAst;
import com.hunnit_beasts.guard.core.dsl.parser.DslParser;
import com.hunnit_beasts.guard.core.dsl.parser.DslParser.ParseResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** G4: 파서 진단. 평가에 쓰이는 AST 는 진단 기능 추가 전과 같아야 한다. */
class DslParserDiagnosticsTest {

    private final DslParser parser = new DslParser();

    @Test
    @DisplayName("배포용 schema.doro 는 진단 없이 파싱된다 (비활성 TTU 항 포함)")
    void shippedDefaultSchemaIsClean() throws Exception {
        String dsl = new String(getClass().getResourceAsStream("/schema.doro").readAllBytes(), StandardCharsets.UTF_8);
        assertThat(parser.parseWithDiagnostics(dsl).diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("이해하지 못해 무시된 줄은 줄 번호와 함께 보고된다. 주석/빈 줄/단독 '{' 는 보고하지 않는다")
    void unknownLinesAreReported() {
        String dsl = """
                # comment
                // another

                type user
                {
                  relation self: user
                  bogus line here
                }
                """;
        ParseResult result = parser.parseWithDiagnostics(dsl);
        assertThat(result.diagnostics()).hasSize(1);
        assertThat(result.diagnostics().get(0).line()).isEqualTo(7);
        assertThat(result.diagnostics().get(0).message()).contains("bogus line here");
    }

    @Test
    @DisplayName("같은 타입에서 나중에 선언된 릴레이션을 먼저 참조하면(전방 참조) 보고된다")
    void forwardReferenceIsReported() {
        String dsl = """
                type user {}
                type doc {
                  relation viewer: user | editor
                  relation editor: user
                }
                """;
        ParseResult result = parser.parseWithDiagnostics(dsl);
        assertThat(result.diagnostics()).hasSize(1);
        assertThat(result.diagnostics().get(0).line()).isEqualTo(3);
        assertThat(result.diagnostics().get(0).message()).contains("editor");
    }

    @Test
    @DisplayName("선언되지 않은 타입 이름(오타)은 보고되고, TTU 항은 보고되지 않는다")
    void typoIsReportedButTtuIsNot() {
        String dsl = """
                type user {}
                type doc {
                  relation owner: usr
                  relation viewer: owner | parent#viewer | nothing#declared
                }
                """;
        ParseResult result = parser.parseWithDiagnostics(dsl);
        assertThat(result.diagnostics()).hasSize(1);
        assertThat(result.diagnostics().get(0).line()).isEqualTo(3);
        assertThat(result.diagnostics().get(0).message()).contains("usr");
    }

    @Test
    @DisplayName("parse() 는 진단과 무관하게 이전과 같은 AST 를 만든다 (전방 참조는 그대로 직접 항)")
    void parseStillBehavesTheSame() {
        String dsl = """
                type doc {
                  relation viewer: user | editor
                  relation editor: user
                  garbage
                }
                """;
        SchemaAst ast = parser.parse(dsl);
        assertThat(ast).isEqualTo(parser.parseWithDiagnostics(dsl).ast());
        assertThat(ast.getType("doc").relations()).containsOnlyKeys("viewer", "editor");
        assertThat(ast.getType("doc").relations().get("viewer").expression().toString())
                .contains(new DirectRelationNode("editor").toString());
    }
}

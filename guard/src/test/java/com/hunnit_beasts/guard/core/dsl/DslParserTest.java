package com.hunnit_beasts.guard.core.dsl;

import com.hunnit_beasts.guard.core.dsl.ast.AstNodes.*;
import com.hunnit_beasts.guard.core.dsl.parser.DslParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DslParserTest {

    private final DslParser dslParser = new DslParser();

    @Test
    @DisplayName("DSL 파서: Zanzibar 타입, Userset Rewrite, TTU 문법 정상 파싱 검증")
    void testParseZanzibarDsl() {
        String dsl = """
                # Sample Schema
                type user {}
                
                type group {
                  relation member: user | group#member
                }
                
                type folder {
                  relation parent: folder
                  relation owner: user
                  relation editor: user | owner
                  relation viewer: user | editor | parent#viewer
                }
                
                type document {
                  relation parent: folder
                  relation owner: user
                  relation editor: user | group#member | owner
                  relation viewer: user | editor | parent#viewer
                }
                """;

        SchemaAst schema = dslParser.parse(dsl);
        assertThat(schema.types()).containsKey("user");
        assertThat(schema.types()).containsKey("group");
        assertThat(schema.types()).containsKey("folder");
        assertThat(schema.types()).containsKey("document");

        TypeAst docType = schema.getType("document");
        assertThat(docType.relations()).containsKey("viewer");

        RelationAst viewerRel = docType.relations().get("viewer");
        assertThat(viewerRel.expression()).isInstanceOf(UnionNode.class);

        UnionNode unionNode = (UnionNode) viewerRel.expression();
        assertThat(unionNode.children()).hasSize(3);

        // parent#viewer는 TTU(TupleToUsersetNode)로 인식되어야 함
        boolean hasTtu = unionNode.children().stream().anyMatch(n -> n instanceof TupleToUsersetNode ttu &&
                ttu.tuplesetRelation().equals("parent") && ttu.computedUsersetRelation().equals("viewer"));
        assertThat(hasTtu).isTrue();
    }
}

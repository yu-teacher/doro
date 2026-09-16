package com.hunnit_beasts.guard.core.dsl;

import com.hunnit_beasts.guard.common.exception.ErrorCode;
import com.hunnit_beasts.guard.common.exception.GuardException;
import com.hunnit_beasts.guard.core.dsl.ast.AstNodes.SchemaAst;
import com.hunnit_beasts.guard.core.dsl.parser.DslParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuardDslEdgeCaseTest {

    private final DslParser dslParser = new DslParser();

    @Test
    @DisplayName("DSL 파서 엣지케이스: 빈 문자열 또는 null 입력 시 빈 스키마 반환")
    void testEmptyDslInput() {
        SchemaAst ast1 = dslParser.parse(null);
        assertThat(ast1.types()).isEmpty();

        SchemaAst ast2 = dslParser.parse("   \n\n  ");
        assertThat(ast2.types()).isEmpty();
    }

    @Test
    @DisplayName("DSL 파서 엣지케이스: 타입 블록 외부에서 릴레이션 선언 시 INVALID_SYNTAX 예외 발생")
    void testRelationOutsideTypeBlock() {
        String invalidDsl = """
                relation viewer: user
                """;

        assertThatThrownBy(() -> dslParser.parse(invalidDsl))
                .isInstanceOf(GuardException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_SYNTAX);
    }

    @Test
    @DisplayName("DSL 파서 엣지케이스: 릴레이션 정의에 콜론(:) 누락 시 INVALID_SYNTAX 예외 발생")
    void testMissingColonInRelation() {
        String invalidDsl = """
                type document {
                  relation viewer user
                }
                """;

        assertThatThrownBy(() -> dslParser.parse(invalidDsl))
                .isInstanceOf(GuardException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_SYNTAX);
    }

    @Test
    @DisplayName("DSL 파서 엣지케이스: 타입 이름 누락 시 INVALID_SYNTAX 예외 발생")
    void testMissingTypeName() {
        String invalidDsl = """
                type {
                  relation viewer: user
                }
                """;

        assertThatThrownBy(() -> dslParser.parse(invalidDsl))
                .isInstanceOf(GuardException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_SYNTAX);
    }
}

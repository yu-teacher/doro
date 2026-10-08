package com.hunnit_beasts.auth.core.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalCallerTokensTest {

    private static final String BLOG = "b".repeat(40);
    private static final String PARTY = "p".repeat(40);

    @Test
    @DisplayName("이름:토큰 목록을 읽고, 맞는 토큰의 호출자 이름을 돌려준다")
    void resolvesCaller() {
        InternalCallerTokens tokens = InternalCallerTokens.parse("blog:" + BLOG + ", party:" + PARTY);
        assertThat(tokens.size()).isEqualTo(2);
        assertThat(tokens.callerOf(BLOG)).contains("blog");
        assertThat(tokens.callerOf(PARTY)).contains("party");
    }

    @Test
    @DisplayName("틀린 토큰, 빈 토큰, 없는 토큰, 한 글자만 다른 토큰은 거부한다")
    void rejectsUnknown() {
        InternalCallerTokens tokens = InternalCallerTokens.parse("blog:" + BLOG);
        assertThat(tokens.callerOf("x".repeat(40))).isEmpty();
        assertThat(tokens.callerOf("")).isEmpty();
        assertThat(tokens.callerOf(null)).isEmpty();
        assertThat(tokens.callerOf(BLOG.substring(0, 39) + "c")).isEmpty();
        assertThat(tokens.callerOf(BLOG + "b")).isEmpty();
        assertThat(tokens.callerOf(BLOG.substring(0, 39))).isEmpty();
    }

    @Test
    @DisplayName("설정이 비어 있으면 어떤 토큰도 통과하지 못한다")
    void emptyConfigurationMatchesNothing() {
        for (String raw : new String[]{null, "", "   ", " , "}) {
            InternalCallerTokens tokens = InternalCallerTokens.parse(raw);
            assertThat(tokens.isEmpty()).isTrue();
            assertThat(tokens.callerOf(BLOG)).isEmpty();
            assertThat(tokens.callerOf("")).isEmpty();
        }
    }

    @Test
    @DisplayName("너무 짧은 토큰, 형식이 틀린 항목, 중복 이름은 기동 때 거부한다(토큰 값은 오류 메시지에 담지 않는다)")
    void rejectsMalformedConfiguration() {
        assertThatThrownBy(() -> InternalCallerTokens.parse("blog:short"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("너무 짧").hasMessageNotContaining("short");
        assertThatThrownBy(() -> InternalCallerTokens.parse(BLOG)).isInstanceOf(IllegalStateException.class).hasMessageNotContaining(BLOG);
        assertThatThrownBy(() -> InternalCallerTokens.parse(":" + BLOG)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> InternalCallerTokens.parse("blog:")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> InternalCallerTokens.parse("blog:" + BLOG + ",blog:" + PARTY))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("중복");
    }

    @Test
    @DisplayName("토큰에 콜론이 있어도 첫 콜론 뒤를 모두 토큰으로 본다")
    void tokenMayContainColon() {
        String token = "a:b".repeat(15);
        assertThat(InternalCallerTokens.parse("svc:" + token).callerOf(token)).contains("svc");
    }

    @Test
    @DisplayName("모드 문자열: 비어 있으면 ENFORCE(안전한 기본), 대소문자·공백 허용, 모르는 값은 기동 실패")
    void parsesMode() {
        assertThat(InternalAuthMode.parse(null)).isEqualTo(InternalAuthMode.ENFORCE);
        assertThat(InternalAuthMode.parse("")).isEqualTo(InternalAuthMode.ENFORCE);
        assertThat(InternalAuthMode.parse(" warn ")).isEqualTo(InternalAuthMode.WARN);
        assertThat(InternalAuthMode.parse("OFF")).isEqualTo(InternalAuthMode.OFF);
        assertThatThrownBy(() -> InternalAuthMode.parse("enforced")).isInstanceOf(IllegalStateException.class);
    }
}

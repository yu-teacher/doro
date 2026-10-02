package com.hunnit_beasts.auth.common.log;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class LogMaskingTest {

    @Test
    @DisplayName("A7: 이메일은 첫 글자와 도메인만 남기고 마스킹된다")
    void masksEmail() {
        assertThat(LogMasking.maskEmail("alice@example.com")).isEqualTo("a***@example.com");
        assertThat(LogMasking.maskEmail("  Bob@Doro.Local ")).isEqualTo("B***@Doro.Local");
        assertThat(LogMasking.maskEmail("x@y.z")).isEqualTo("x***@y.z");
    }

    @Test
    @DisplayName("A7: null/빈 값/형식 오류는 *** 로 처리된다")
    void masksInvalidEmail() {
        assertThat(LogMasking.maskEmail(null)).isEqualTo("***");
        assertThat(LogMasking.maskEmail(" ")).isEqualTo("***");
        assertThat(LogMasking.maskEmail("no-at-sign")).isEqualTo("***");
        assertThat(LogMasking.maskEmail("@nolocal.com")).isEqualTo("***");
        assertThat(LogMasking.maskEmail("nodomain@")).isEqualTo("***");
    }

    @Test
    @DisplayName("A7: 이름은 첫 글자만 남긴다")
    void masksName() {
        assertThat(LogMasking.maskName("홍길동")).isEqualTo("홍***");
        assertThat(LogMasking.maskName("Alice")).isEqualTo("A***");
        assertThat(LogMasking.maskName(null)).isEqualTo("***");
    }

    private static final Pattern LOG_CALL = Pattern.compile("\\blog\\.(trace|debug|info|warn|error)\\(.*?\\);", Pattern.DOTALL);
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");
    private static final Pattern MASKED_CALL = Pattern.compile("LogMasking\\.mask\\w+\\((?:[^()]|\\([^()]*\\))*\\)");
    private static final Pattern RAW_PII = Pattern.compile("get(Email|Name)\\(\\)|\\bemail\\b|\\b(request|req|user)\\.(email|name)\\(\\)");

    @Test
    @DisplayName("A7: 로그 호출에 마스킹 없이 이메일/이름을 넘기는 곳이 없다 (소스 스캔)")
    void noLogCallPrintsRawEmailOrName() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Paths.get("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                Matcher m = LOG_CALL.matcher(source);
                while (m.find()) {
                    String call = STRING_LITERAL.matcher(m.group()).replaceAll("\"\"");
                    call = MASKED_CALL.matcher(call).replaceAll("");
                    if (RAW_PII.matcher(call).find()) {
                        offenders.add(file + ": " + m.group().replaceAll("\\s+", " "));
                    }
                }
            }
        }
        assertThat(offenders).isEmpty();
    }
}

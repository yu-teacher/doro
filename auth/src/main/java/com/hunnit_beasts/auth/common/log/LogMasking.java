package com.hunnit_beasts.auth.common.log;

/** 로그에 남기는 개인정보(이메일·이름)를 마스킹한다. 원문을 로그 문자열에 직접 이어 붙이지 않는다. */
public final class LogMasking {

    private static final String MASK = "***";

    private LogMasking() {
    }

    /** {@code alice@example.com} -> {@code a***@example.com}. 형식이 올바르지 않으면 {@code ***}. */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return MASK;
        }
        String trimmed = email.trim();
        int at = trimmed.indexOf('@');
        if (at <= 0 || at == trimmed.length() - 1) {
            return MASK;
        }
        int firstCodePoint = trimmed.codePointAt(0);
        return new String(Character.toChars(firstCodePoint)) + MASK + trimmed.substring(at);
    }

    /** {@code 홍길동} -> {@code 홍***}. */
    public static String maskName(String name) {
        if (name == null || name.isBlank()) {
            return MASK;
        }
        String trimmed = name.trim();
        return new String(Character.toChars(trimmed.codePointAt(0))) + MASK;
    }
}

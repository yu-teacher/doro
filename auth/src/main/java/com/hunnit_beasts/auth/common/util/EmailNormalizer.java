package com.hunnit_beasts.auth.common.util;

import java.util.Locale;

/** 이메일 정규화(trim + 소문자). 신규 가입 시 저장 형식을 통일한다. 조회는 대소문자 무시로 수행한다. */
public final class EmailNormalizer {

    private EmailNormalizer() {
    }

    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}

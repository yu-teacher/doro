package com.hunnit_beasts.auth.core.internal;

/** 내부 API(/internal/**) 호출자 인증 수준. OFF: 검사 안 함, WARN: 실패를 경고만 하고 통과, ENFORCE: 실패는 401. */
public enum InternalAuthMode {
    OFF,
    WARN,
    ENFORCE;

    public static InternalAuthMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ENFORCE;
        }
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("doro.iam.internal-auth.mode 는 OFF, WARN, ENFORCE 중 하나여야 합니다: " + raw, e);
        }
    }
}

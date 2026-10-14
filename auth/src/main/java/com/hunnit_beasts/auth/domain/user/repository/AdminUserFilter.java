package com.hunnit_beasts.auth.domain.user.repository;

import java.util.Locale;
import java.util.Optional;

/** 관리자 사용자 목록의 필터 탭. 웹 관리자 화면의 탭과 1:1 로 대응한다. */
public enum AdminUserFilter {
    ALL,
    SUPER_ADMIN,
    ADMIN,
    USER,
    /** 2FA 를 켠 계정 */
    TOTP,
    /** 정지됐거나 비밀번호·OTP 연속 실패로 잠긴 계정 */
    SUSPENDED;

    public static Optional<AdminUserFilter> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.of(ALL);
        }
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}

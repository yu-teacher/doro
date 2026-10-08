package com.hunnit_beasts.auth.domain.user.service;

import java.util.regex.Pattern;

/**
 * 프로필 이미지 값의 허용 목록. 이 값은 다른 서비스(블로그 등)가 그대로 화면에 쓰므로
 * javascript:, data:text/html 같은 스킴이 들어가지 못하게 한다. 빈 값(이미지 제거)은 허용한다.
 */
final class ProfileImageUrl {

    /** 이미지 data URL. 포털의 기본 아바타(SVG)와 업로드(JPEG/PNG 등)가 이 형식이다. */
    private static final Pattern IMAGE_DATA_URL =
            Pattern.compile("^data:image/(png|jpeg|gif|webp|svg\\+xml)[;,].*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private ProfileImageUrl() {
    }

    static boolean isAllowed(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        if (value.regionMatches(true, 0, "https://", 0, "https://".length())) {
            return value.length() > "https://".length();
        }
        return IMAGE_DATA_URL.matcher(value).matches();
    }
}

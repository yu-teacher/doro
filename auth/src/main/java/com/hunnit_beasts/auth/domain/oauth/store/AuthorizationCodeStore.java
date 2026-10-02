package com.hunnit_beasts.auth.domain.oauth.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 인가 코드 저장소. 구현체는 코드 원문을 저장하지 않고(SHA-256 해시를 키로 사용) 코드를 1회만 소비할 수 있게 해야 한다.
 */
public interface AuthorizationCodeStore {

    /** 코드를 저장한다. 보관 상한을 넘으면 TOO_MANY_REQUESTS AuthException 을 던질 수 있다. */
    void save(String code, AuthorizationCodeData data);

    /** 코드를 원자적으로 꺼내며 폐기한다. 없거나 만료되었거나 이미 소비되었으면 비어 있다. */
    Optional<AuthorizationCodeData> consume(String code);

    /** 이미 소비된 코드인지(재사용 시도 탐지용). 만료로 사라진 코드와 구분하기 위한 최선의 정보다. */
    boolean wasConsumed(String code);

    /** 만료분을 정리하고 제거한 미사용 코드 수를 반환한다. TTL 로 스스로 정리하는 저장소는 0. */
    int purgeExpired(Instant now);

    /** 현재 보관 중인 미사용 코드 수(알 수 없으면 -1). */
    int pendingCount();

    /** 코드 원문 대신 저장 키에 쓰는 SHA-256 hex. */
    static String hash(String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}

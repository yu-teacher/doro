package com.hunnit_beasts.auth.domain.oauth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * OAuth 클라이언트 시크릿의 생성·해시·검증. 시크릿은 256비트 난수라 느린 해시(BCrypt 등)가 필요 없고,
 * 토큰 요청마다 검증하므로 SHA-256 을 쓴다. 비교는 상수 시간으로 한다.
 */
public final class ClientSecrets {

    /** 시크릿 접두사: 유출된 문자열이 무엇인지 알아보고 비밀 스캐너가 잡기 쉽게 한다. */
    public static final String PREFIX = "dcs_";
    private static final int SECRET_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ClientSecrets() {}

    public static String generate() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다.", e);
        }
    }

    /** presented 가 storedHash 의 시크릿과 같은지 상수 시간으로 비교한다. */
    public static boolean matches(String presented, String storedHash) {
        if (presented == null || storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
                hash(presented).getBytes(StandardCharsets.US_ASCII),
                storedHash.getBytes(StandardCharsets.US_ASCII));
    }
}

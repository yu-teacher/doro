package com.hunnit_beasts.auth.domain.social.provider;

/**
 * 소셜 공급자가 알려 준 사용자. 이메일은 공급자가 소유를 확인한 것(emailVerified)만 계정 연결·가입에 쓴다.
 */
public record SocialProfile(String providerUserId, String email, boolean emailVerified, String name) {
}

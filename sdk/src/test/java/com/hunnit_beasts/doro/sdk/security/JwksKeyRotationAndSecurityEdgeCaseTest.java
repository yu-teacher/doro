package com.hunnit_beasts.doro.sdk.security;

import com.hunnit_beasts.doro.sdk.domain.DoroUser;
import com.hunnit_beasts.doro.sdk.domain.DoroUserContext;
import com.hunnit_beasts.doro.sdk.security.filter.DoroJwtAuthFilter;
import com.hunnit_beasts.doro.sdk.security.jwks.JwksKeyProvider;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class JwksKeyRotationAndSecurityEdgeCaseTest {

    @Test
    @DisplayName("키 회전(Key Rotation): 기존 키(Key-1)에서 새 키(Key-2)로 교체되어도 동적으로 새 공개키를 등록/검증")
    void testKeyRotationSeamlessVerification() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);

        KeyPair oldKeyPair = keyGen.generateKeyPair();
        KeyPair newKeyPair = keyGen.generateKeyPair();

        JwksKeyProvider keyProvider = new JwksKeyProvider(null);
        keyProvider.registerKey("key-1", oldKeyPair.getPublic());

        DoroJwtAuthFilter filter = new DoroJwtAuthFilter(keyProvider);

        // 키 회전 발생: 새 키(key-2)로 발급된 JWT
        UUID userId = UUID.randomUUID();
        String newToken = Jwts.builder()
                .header().keyId("key-2").and()
                .subject(userId.toString())
                .claim("email", "rotated.user@doro.local")
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(newKeyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();

        // 1. 새 키 등록 전: 인증 실패 (DoroUserContext에 미주입)
        MockHttpServletRequest req1 = new MockHttpServletRequest();
        req1.addHeader("Authorization", "Bearer " + newToken);
        MockHttpServletResponse res1 = new MockHttpServletResponse();

        AtomicReference<DoroUser> userRef1 = new AtomicReference<>();
        filter.doFilter(req1, res1, (req, res) -> userRef1.set(DoroUserContext.getCurrentUser()));
        assertThat(userRef1.get().isAuthenticated()).isFalse();

        // 2. 키 회전 반영: keyProvider에 key-2 등록 (실제 운영에서는 jwksUri 자동 refresh)
        keyProvider.registerKey("key-2", newKeyPair.getPublic());

        // 3. 새 키 등록 후: 즉시 무중단 정상 검증
        MockHttpServletRequest req2 = new MockHttpServletRequest();
        req2.addHeader("Authorization", "Bearer " + newToken);
        MockHttpServletResponse res2 = new MockHttpServletResponse();

        AtomicReference<DoroUser> userRef2 = new AtomicReference<>();
        filter.doFilter(req2, res2, (req, res) -> userRef2.set(DoroUserContext.getCurrentUser()));
        assertThat(userRef2.get().isAuthenticated()).isTrue();
        assertThat(userRef2.get().userId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("보안 엣지케이스: 서명이 변조된 위조 JWT는 즉시 인증 거부")
    void testTamperedSignatureRejected() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair validKeyPair = keyGen.generateKeyPair();
        KeyPair attackerKeyPair = keyGen.generateKeyPair();

        JwksKeyProvider keyProvider = new JwksKeyProvider(null);
        keyProvider.registerKey("doro-valid-key", validKeyPair.getPublic());

        DoroJwtAuthFilter filter = new DoroJwtAuthFilter(keyProvider);

        // 공격자가 자신의 키로 서명한 가짜 토큰 (kid는 서버의 정상 kid 사칭)
        String fakeToken = Jwts.builder()
                .header().keyId("doro-valid-key").and()
                .subject(UUID.randomUUID().toString())
                .claim("email", "hacker@evil.com")
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(attackerKeyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + fakeToken);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<DoroUser> userRef = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> userRef.set(DoroUserContext.getCurrentUser()));

        assertThat(userRef.get().isAuthenticated()).isFalse();
    }

    @Test
    @DisplayName("보안 엣지케이스: 유효기간이 지난 만료된 JWT는 즉시 인증 거부")
    void testExpiredJwtRejected() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();

        JwksKeyProvider keyProvider = new JwksKeyProvider(null);
        keyProvider.registerKey("doro-key-exp", keyPair.getPublic());

        DoroJwtAuthFilter filter = new DoroJwtAuthFilter(keyProvider);

        // 과거 만료된 토큰
        String expiredToken = Jwts.builder()
                .header().keyId("doro-key-exp").and()
                .subject(UUID.randomUUID().toString())
                .claim("email", "expired@doro.local")
                .expiration(new Date(System.currentTimeMillis() - 10000))
                .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + expiredToken);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<DoroUser> userRef = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> userRef.set(DoroUserContext.getCurrentUser()));

        assertThat(userRef.get().isAuthenticated()).isFalse();
    }
}

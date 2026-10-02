package com.hunnit_beasts.doro.sdk.security.filter;

import com.hunnit_beasts.doro.sdk.config.DoroProperties.IssuerValidation;
import com.hunnit_beasts.doro.sdk.domain.DoroUser;
import com.hunnit_beasts.doro.sdk.domain.DoroUserContext;
import com.hunnit_beasts.doro.sdk.security.jwks.JwksKeyProvider;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DoroJwtAuthFilterAudienceTest {

    private static final String KID = "aud-kid";
    private static final String AUDIENCE = "doro-blog";

    private KeyPair keyPair;
    private JwksKeyProvider keyProvider;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
        keyProvider = new JwksKeyProvider(null);
        keyProvider.registerKey(KID, keyPair.getPublic());
    }

    private String token(String... audiences) {
        var builder = Jwts.builder()
                .header().keyId(KID).and()
                .subject(UUID.randomUUID().toString())
                .expiration(new Date(System.currentTimeMillis() + 60_000));
        if (audiences.length == 1) {
            builder.audience().single(audiences[0]);
        } else if (audiences.length > 1) {
            builder.audience().add(java.util.List.of(audiences)).and();
        }
        return builder.signWith(keyPair.getPrivate(), Jwts.SIG.RS256).compact();
    }

    private DoroUser run(DoroJwtAuthFilter filter, String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        AtomicReference<DoroUser> ref = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> ref.set(DoroUserContext.getCurrentUser()));
        return ref.get();
    }

    private DoroJwtAuthFilter filter(String audience) {
        return new DoroJwtAuthFilter(keyProvider, null, IssuerValidation.OFF, "", 0, audience);
    }

    @Test
    @DisplayName("audience 가 비어 있으면(기본) aud 가 없는 일반 액세스 토큰을 그대로 받는다")
    void emptyAudienceAcceptsPlainAccessTokens() throws Exception {
        assertThat(run(filter(""), token()).isAuthenticated()).isTrue();
        assertThat(run(filter(null), token()).isAuthenticated()).isTrue();
    }

    @Test
    @DisplayName("audience 를 설정하지 않았다면 aud 가 있는 토큰(OIDC id_token 등)은 액세스 토큰으로 인정하지 않는다 (토큰 혼동 방지)")
    void tokensWithAudAreNotAccessTokensUnlessAudienceIsConfigured() throws Exception {
        assertThat(run(filter(""), token("some-client")).isAuthenticated()).isFalse();
        assertThat(run(filter(null), token("a", "b")).isAuthenticated()).isFalse();
    }

    @Test
    @DisplayName("audience 설정 시 aud 문자열이 일치하면 허용")
    void matchingStringAudienceAccepted() throws Exception {
        assertThat(run(filter(AUDIENCE), token(AUDIENCE)).isAuthenticated()).isTrue();
    }

    @Test
    @DisplayName("audience 설정 시 aud 배열에 값이 포함되면 허용")
    void matchingArrayAudienceAccepted() throws Exception {
        assertThat(run(filter(AUDIENCE), token("other", AUDIENCE)).isAuthenticated()).isTrue();
    }

    @Test
    @DisplayName("audience 설정 시 aud 불일치/누락 토큰은 거부(익명 계속)")
    void mismatchedOrMissingAudienceRejected() throws Exception {
        assertThat(run(filter(AUDIENCE), token("other")).isAuthenticated()).isFalse();
        assertThat(run(filter(AUDIENCE), token("a", "b")).isAuthenticated()).isFalse();
        assertThat(run(filter(AUDIENCE), token()).isAuthenticated()).isFalse();
    }
}

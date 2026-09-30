package com.hunnit_beasts.doro.sdk.security.filter;

import com.hunnit_beasts.doro.sdk.config.DoroProperties.IssuerValidation;
import com.hunnit_beasts.doro.sdk.domain.DoroUser;
import com.hunnit_beasts.doro.sdk.domain.DoroUserContext;
import com.hunnit_beasts.doro.sdk.security.jwks.JwksKeyProvider;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DoroJwtAuthFilterHardeningTest {

    private static final String KID = "hardening-kid";
    private static final String ISSUER = "https://auth.doro.local";
    private static final String COOKIE = "doro_access";

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

    private String token(String issuer, Date expiration) {
        var builder = Jwts.builder()
                .header().keyId(KID).and()
                .subject(UUID.randomUUID().toString())
                .claim("email", "h@doro.local");
        if (issuer != null) {
            builder.issuer(issuer);
        }
        if (expiration != null) {
            builder.expiration(expiration);
        }
        return builder.signWith(keyPair.getPrivate(), Jwts.SIG.RS256).compact();
    }

    private DoroUser run(DoroJwtAuthFilter filter, MockHttpServletRequest request) throws Exception {
        AtomicReference<DoroUser> ref = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> ref.set(DoroUserContext.getCurrentUser()));
        return ref.get();
    }

    private Date inFuture() {
        return new Date(System.currentTimeMillis() + 60_000);
    }

    @Test
    @DisplayName("exp 클레임이 없는 토큰은 인증 거부")
    void tokenWithoutExpRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token(null, null));

        assertThat(run(new DoroJwtAuthFilter(keyProvider), request).isAuthenticated()).isFalse();
    }

    @Test
    @DisplayName("쿠키 이름이 설정되면 Authorization 헤더가 없을 때 쿠키의 토큰으로 인증")
    void cookieTokenAccepted() throws Exception {
        DoroJwtAuthFilter filter = new DoroJwtAuthFilter(keyProvider, ISSUER, IssuerValidation.OFF, COOKIE, 0);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(COOKIE, token(ISSUER, inFuture())));

        assertThat(run(filter, request).isAuthenticated()).isTrue();
    }

    @Test
    @DisplayName("쿠키 이름이 설정되지 않으면 쿠키는 무시")
    void cookieIgnoredByDefault() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(COOKIE, token(ISSUER, inFuture())));

        assertThat(run(new DoroJwtAuthFilter(keyProvider), request).isAuthenticated()).isFalse();
    }

    @Test
    @DisplayName("issuer-validation=ENFORCE 이면 iss 불일치 토큰 거부, WARN 이면 허용")
    void issuerValidationModes() throws Exception {
        String wrongIssuer = token("https://evil.example", inFuture());

        MockHttpServletRequest enforceRequest = new MockHttpServletRequest();
        enforceRequest.addHeader("Authorization", "Bearer " + wrongIssuer);
        assertThat(run(new DoroJwtAuthFilter(keyProvider, ISSUER, IssuerValidation.ENFORCE, "", 0), enforceRequest)
                .isAuthenticated()).isFalse();

        MockHttpServletRequest warnRequest = new MockHttpServletRequest();
        warnRequest.addHeader("Authorization", "Bearer " + wrongIssuer);
        assertThat(run(new DoroJwtAuthFilter(keyProvider, ISSUER, IssuerValidation.WARN, "", 0), warnRequest)
                .isAuthenticated()).isTrue();
    }

    @Test
    @DisplayName("비정상 X-Trace-Id 는 새 UUID 로 대체하고, 다른 라이브러리의 MDC 키는 지우지 않는다")
    void traceIdSanitizedAndMdcPreserved() throws Exception {
        MDC.put("otherLibraryKey", "keep");
        try {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Trace-Id", "bad\r\nInjected: header");
            MockHttpServletResponse response = new MockHttpServletResponse();

            new DoroJwtAuthFilter(keyProvider).doFilter(request, response, (req, res) -> { });

            String echoed = response.getHeader("X-Trace-Id");
            assertThat(echoed).doesNotContain("\n").doesNotContain("Injected");
            assertThat(UUID.fromString(echoed)).isNotNull();
            assertThat(MDC.get("otherLibraryKey")).isEqualTo("keep");
            assertThat(MDC.get("traceId")).isNull();
        } finally {
            MDC.remove("otherLibraryKey");
        }
    }
}

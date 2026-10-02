package com.hunnit_beasts.doro.sdk.security.filter;

import com.hunnit_beasts.doro.sdk.config.DoroProperties;
import com.hunnit_beasts.doro.sdk.config.DoroProperties.IssuerValidation;
import com.hunnit_beasts.doro.sdk.config.DoroProperties.RevocationCheck;
import com.hunnit_beasts.doro.sdk.domain.DoroUser;
import com.hunnit_beasts.doro.sdk.domain.DoroUserContext;
import com.hunnit_beasts.doro.sdk.security.jwks.JwksKeyProvider;
import com.hunnit_beasts.doro.sdk.security.revocation.FakeIamServer;
import com.hunnit_beasts.doro.sdk.security.revocation.FakeIamServer.MutableClock;
import com.hunnit_beasts.doro.sdk.security.revocation.SessionRevocationChecker;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DoroJwtAuthFilterRevocationTest {

    private static final String KID = "rev-kid";
    private static final String COOKIE = "doro_at";

    private KeyPair keyPair;
    private JwksKeyProvider keyProvider;
    private FakeIamServer iam;
    private MutableClock clock;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
        keyProvider = new JwksKeyProvider(null);
        keyProvider.registerKey(KID, keyPair.getPublic());
        iam = new FakeIamServer();
        clock = new MutableClock();
    }

    @AfterEach
    void tearDown() {
        iam.close();
    }

    private String token(UUID sid) {
        var builder = Jwts.builder()
                .header().keyId(KID).and()
                .subject(UUID.randomUUID().toString())
                .claim("email", "u@doro.local")
                .expiration(new Date(System.currentTimeMillis() + 60_000));
        if (sid != null) {
            builder.claim("sid", sid.toString());
        }
        return builder.signWith(keyPair.getPrivate(), Jwts.SIG.RS256).compact();
    }

    private DoroJwtAuthFilter filter(RevocationCheck mode, boolean failOpen, long cacheSeconds, int timeoutMillis) {
        return new DoroJwtAuthFilter(keyProvider, null, IssuerValidation.OFF, COOKIE, 0, "",
                mode, new SessionRevocationChecker(iam.url(), cacheSeconds, timeoutMillis, clock), failOpen);
    }

    private DoroJwtAuthFilter filter(RevocationCheck mode, boolean failOpen) {
        return filter(mode, failOpen, 30, 1000);
    }

    private DoroUser run(DoroJwtAuthFilter filter, String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return run(filter, request);
    }

    private DoroUser run(DoroJwtAuthFilter filter, MockHttpServletRequest request) throws Exception {
        AtomicReference<DoroUser> ref = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> ref.set(DoroUserContext.getCurrentUser()));
        return ref.get();
    }

    @Test
    @DisplayName("ENFORCE: 폐기된 세션(401)의 토큰은 익명 처리된다")
    void enforceRevokedIsAnonymous() throws Exception {
        iam.respondWith(401);
        DoroUser user = run(filter(RevocationCheck.ENFORCE, true), token(UUID.randomUUID()));
        assertThat(user.isAuthenticated()).isFalse();
    }

    @Test
    @DisplayName("ENFORCE: 정지된 계정(403)의 토큰도 익명 처리된다")
    void enforceForbiddenIsAnonymous() throws Exception {
        iam.respondWith(403);
        assertThat(run(filter(RevocationCheck.ENFORCE, true), token(UUID.randomUUID())).isAuthenticated()).isFalse();
    }

    @Test
    @DisplayName("ENFORCE: 유효한 세션(204)은 인증되고 IAM 에는 같은 토큰이 전달된다")
    void enforceActivePasses() throws Exception {
        String token = token(UUID.randomUUID());
        DoroUser user = run(filter(RevocationCheck.ENFORCE, true), token);
        assertThat(user.isAuthenticated()).isTrue();
        assertThat(iam.authorizationHeaders()).containsExactly("Bearer " + token);
    }

    @Test
    @DisplayName("쿠키로 받은 토큰도 같은 토큰으로 IAM 에 Bearer 로 전달된다")
    void cookieTokenIsForwardedAsBearer() throws Exception {
        String token = token(UUID.randomUUID());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(COOKIE, token));
        DoroUser user = run(filter(RevocationCheck.ENFORCE, true), request);
        assertThat(user.isAuthenticated()).isTrue();
        assertThat(iam.authorizationHeaders()).containsExactly("Bearer " + token);
    }

    @Test
    @DisplayName("WARN: 폐기된 세션이어도 요청은 통과한다")
    void warnRevokedProceeds() throws Exception {
        iam.respondWith(401);
        assertThat(run(filter(RevocationCheck.WARN, true), token(UUID.randomUUID())).isAuthenticated()).isTrue();
        assertThat(iam.hits()).isEqualTo(1);
    }

    @Test
    @DisplayName("활성 응답은 캐시 기간 동안 한 번만 조회하고, 만료 후 폐기되면 그때부터 익명 처리된다")
    void activeCachedThenRevoked() throws Exception {
        UUID sid = UUID.randomUUID();
        DoroJwtAuthFilter filter = filter(RevocationCheck.ENFORCE, true, 30, 1000);
        assertThat(run(filter, token(sid)).isAuthenticated()).isTrue();
        assertThat(run(filter, token(sid)).isAuthenticated()).isTrue();
        assertThat(iam.hits()).isEqualTo(1);

        iam.respondWith(401);
        assertThat(run(filter, token(sid)).isAuthenticated()).isTrue(); // 아직 캐시 유효
        clock.advanceSeconds(31);
        assertThat(run(filter, token(sid)).isAuthenticated()).isFalse();
        assertThat(iam.hits()).isEqualTo(2);
    }

    @Test
    @DisplayName("폐기 판정은 다시 묻지 않고 계속 익명 처리된다")
    void revokedIsNotAskedAgain() throws Exception {
        UUID sid = UUID.randomUUID();
        DoroJwtAuthFilter filter = filter(RevocationCheck.ENFORCE, true);
        iam.respondWith(401);
        assertThat(run(filter, token(sid)).isAuthenticated()).isFalse();
        iam.respondWith(204);
        clock.advanceSeconds(30);
        assertThat(run(filter, token(sid)).isAuthenticated()).isFalse();
        assertThat(iam.hits()).isEqualTo(1);
    }

    @Test
    @DisplayName("IAM 이 내려가 있으면 fail-open 은 통과, fail-closed(ENFORCE)는 익명, fail-closed 라도 WARN 모드는 통과")
    void iamDown() throws Exception {
        iam.close();
        assertThat(run(filter(RevocationCheck.ENFORCE, true), token(UUID.randomUUID())).isAuthenticated()).isTrue();
        assertThat(run(filter(RevocationCheck.ENFORCE, false), token(UUID.randomUUID())).isAuthenticated()).isFalse();
        assertThat(run(filter(RevocationCheck.WARN, false), token(UUID.randomUUID())).isAuthenticated()).isTrue();
    }

    @Test
    @DisplayName("IAM 5xx: fail-open 은 통과, fail-closed 는 익명이며 장애 응답은 캐시되지 않는다")
    void iamServerError() throws Exception {
        iam.respondWith(500);
        UUID sid = UUID.randomUUID();
        assertThat(run(filter(RevocationCheck.ENFORCE, true), token(sid)).isAuthenticated()).isTrue();
        DoroJwtAuthFilter closed = filter(RevocationCheck.ENFORCE, false);
        assertThat(run(closed, token(sid)).isAuthenticated()).isFalse();

        iam.respondWith(204);
        assertThat(run(closed, token(sid)).isAuthenticated()).isTrue();
    }

    @Test
    @DisplayName("IAM 타임아웃: fail-open 은 통과, fail-closed 는 익명")
    void iamTimeout() throws Exception {
        iam.delay(1500);
        assertThat(run(filter(RevocationCheck.ENFORCE, true, 30, 200), token(UUID.randomUUID())).isAuthenticated()).isTrue();
        assertThat(run(filter(RevocationCheck.ENFORCE, false, 30, 200), token(UUID.randomUUID())).isAuthenticated()).isFalse();
    }

    @Test
    @DisplayName("모드 OFF 면 IAM 을 전혀 호출하지 않는다 (기존 생성자 포함)")
    void offMakesNoNetworkCall() throws Exception {
        iam.respondWith(401);
        UUID sid = UUID.randomUUID();
        assertThat(run(filter(RevocationCheck.OFF, true), token(sid)).isAuthenticated()).isTrue();
        assertThat(run(new DoroJwtAuthFilter(keyProvider), token(sid)).isAuthenticated()).isTrue();
        assertThat(run(new DoroJwtAuthFilter(keyProvider, null, IssuerValidation.OFF, "", 0, ""), token(sid)).isAuthenticated())
                .isTrue();
        assertThat(iam.hits()).isZero();
    }

    @Test
    @DisplayName("sid 클레임이 없는 토큰은 확인을 건너뛰고 통과한다")
    void sidlessTokenIsSkipped() throws Exception {
        iam.respondWith(401);
        DoroJwtAuthFilter filter = filter(RevocationCheck.ENFORCE, false);
        assertThat(run(filter, token(null)).isAuthenticated()).isTrue();
        assertThat(run(filter, token(null)).isAuthenticated()).isTrue();
        assertThat(iam.hits()).isZero();
    }

    @Test
    @DisplayName("서명이 잘못된 토큰은 IAM 을 호출하지 않고 익명 처리된다")
    void invalidTokenNeverReachesIam() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        String forged = Jwts.builder().header().keyId(KID).and()
                .subject(UUID.randomUUID().toString()).claim("sid", UUID.randomUUID().toString())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(gen.generateKeyPair().getPrivate(), Jwts.SIG.RS256).compact();
        assertThat(run(filter(RevocationCheck.ENFORCE, true), forged).isAuthenticated()).isFalse();
        assertThat(iam.hits()).isZero();
    }

    @Test
    @DisplayName("같은 sid 의 동시 요청은 IAM 호출 한 번으로 처리된다")
    void concurrentRequestsCauseSingleCall() throws Exception {
        iam.delay(300);
        UUID sid = UUID.randomUUID();
        DoroJwtAuthFilter filter = filter(RevocationCheck.ENFORCE, true, 30, 2000);
        int threads = 12;
        var pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<DoroUser>>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return run(filter, token(sid));
            }));
        }
        start.countDown();
        for (var f : futures) {
            assertThat(f.get().isAuthenticated()).isTrue();
        }
        pool.shutdownNow();
        assertThat(iam.hits()).isEqualTo(1);
    }

    @Test
    @DisplayName("속성 기본값은 기존 동작을 바꾸지 않는다(OFF, 빈 URL, 30s, 2000ms, fail-open)이고 yaml/env 키로 바인딩된다")
    void propertyDefaultsAndBinding() {
        DoroProperties.IamProperties defaults = new DoroProperties.IamProperties();
        assertThat(defaults.getRevocationCheck()).isEqualTo(RevocationCheck.OFF);
        assertThat(defaults.getRevocationUrl()).isEmpty();
        assertThat(defaults.getRevocationCacheSeconds()).isEqualTo(30);
        assertThat(defaults.getRevocationTimeoutMillis()).isEqualTo(2000);
        assertThat(defaults.isRevocationFailOpen()).isTrue();

        DoroProperties bound = new Binder(new MapConfigurationPropertySource(Map.of(
                "doro.iam.revocation-check", "ENFORCE",
                "doro.iam.revocation-url", "http://iam/x",
                "doro.iam.revocation-cache-seconds", "5",
                "doro.iam.revocation-timeout-millis", "300",
                "doro.iam.revocation-fail-open", "false")))
                .bind("doro", DoroProperties.class).get();
        assertThat(bound.getIam().getRevocationCheck()).isEqualTo(RevocationCheck.ENFORCE);
        assertThat(bound.getIam().getRevocationUrl()).isEqualTo("http://iam/x");
        assertThat(bound.getIam().getRevocationCacheSeconds()).isEqualTo(5);
        assertThat(bound.getIam().getRevocationTimeoutMillis()).isEqualTo(300);
        assertThat(bound.getIam().isRevocationFailOpen()).isFalse();
    }
}

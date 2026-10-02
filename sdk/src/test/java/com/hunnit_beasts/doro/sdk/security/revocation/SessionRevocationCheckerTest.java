package com.hunnit_beasts.doro.sdk.security.revocation;

import com.hunnit_beasts.doro.sdk.security.revocation.FakeIamServer.MutableClock;
import com.hunnit_beasts.doro.sdk.security.revocation.SessionRevocationChecker.Verdict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class SessionRevocationCheckerTest {

    private static final long EXP_OFFSET_MILLIS = 15 * 60_000L;

    private FakeIamServer iam;
    private MutableClock clock;

    @BeforeEach
    void setUp() throws Exception {
        iam = new FakeIamServer();
        clock = new MutableClock();
    }

    @AfterEach
    void tearDown() {
        iam.close();
    }

    private SessionRevocationChecker checker(long cacheSeconds, int timeoutMillis) {
        return new SessionRevocationChecker(iam.url(), cacheSeconds, timeoutMillis, clock);
    }

    private long exp() {
        return clock.millis() + EXP_OFFSET_MILLIS;
    }

    @Test
    @DisplayName("204 는 ACTIVE 이고 캐시 기간 동안은 서버를 다시 호출하지 않으며, 만료 후에는 다시 묻는다")
    void activeIsCachedThenExpires() {
        SessionRevocationChecker checker = checker(30, 1000);
        UUID sid = UUID.randomUUID();

        assertThat(checker.check(sid, "tok", exp())).isEqualTo(Verdict.ACTIVE);
        clock.advanceSeconds(29);
        assertThat(checker.check(sid, "tok", exp())).isEqualTo(Verdict.ACTIVE);
        assertThat(iam.hits()).isEqualTo(1);

        clock.advanceSeconds(2);
        assertThat(checker.check(sid, "tok", exp())).isEqualTo(Verdict.ACTIVE);
        assertThat(iam.hits()).isEqualTo(2);
    }

    @Test
    @DisplayName("활성 캐시가 만료된 뒤 폐기되었으면 REVOKED 로 바뀐다")
    void revocationDetectedAfterCacheExpiry() {
        SessionRevocationChecker checker = checker(30, 1000);
        UUID sid = UUID.randomUUID();
        assertThat(checker.check(sid, "tok", exp())).isEqualTo(Verdict.ACTIVE);

        iam.respondWith(401);
        clock.advanceSeconds(31);
        assertThat(checker.check(sid, "tok", exp())).isEqualTo(Verdict.REVOKED);
    }

    @Test
    @DisplayName("401/403 은 REVOKED 이고 토큰 exp 까지 다시 묻지 않는다 (서버가 이후 204 로 바뀌어도 유지)")
    void revokedIsCachedUntilTokenExpiry() {
        SessionRevocationChecker checker = checker(1, 1000);
        UUID sid = UUID.randomUUID();
        long exp = exp();
        iam.respondWith(401);

        assertThat(checker.check(sid, "tok", exp)).isEqualTo(Verdict.REVOKED);
        iam.respondWith(204);
        clock.advanceSeconds(600);
        assertThat(checker.check(sid, "tok", exp)).isEqualTo(Verdict.REVOKED);
        assertThat(iam.hits()).isEqualTo(1);

        clock.advanceSeconds(301); // exp(900s) 경과
        assertThat(checker.check(sid, "tok", exp())).isEqualTo(Verdict.ACTIVE);
        assertThat(iam.hits()).isEqualTo(2);

        iam.respondWith(403);
        UUID other = UUID.randomUUID();
        assertThat(checker.check(other, "tok", exp())).isEqualTo(Verdict.REVOKED);
    }

    @Test
    @DisplayName("5xx / 예상 밖 상태 / 연결 실패는 UNAVAILABLE 이며 캐시하지 않는다")
    void unavailableIsNotCached() {
        SessionRevocationChecker checker = checker(30, 1000);
        UUID sid = UUID.randomUUID();

        iam.respondWith(503);
        assertThat(checker.check(sid, "tok", exp())).isEqualTo(Verdict.UNAVAILABLE);
        iam.respondWith(404);
        assertThat(checker.check(sid, "tok", exp())).isEqualTo(Verdict.UNAVAILABLE);
        iam.respondWith(200);
        assertThat(checker.check(sid, "tok", exp())).isEqualTo(Verdict.UNAVAILABLE);
        assertThat(iam.hits()).isEqualTo(3);

        iam.respondWith(204);
        assertThat(checker.check(sid, "tok", exp())).isEqualTo(Verdict.ACTIVE);

        iam.close();
        UUID other = UUID.randomUUID();
        assertThat(checker.check(other, "tok", exp())).isEqualTo(Verdict.UNAVAILABLE);
    }

    @Test
    @DisplayName("타임아웃이면 UNAVAILABLE 이고 설정한 타임아웃 근처에서 반환한다")
    void timeoutIsUnavailable() {
        iam.delay(1500);
        SessionRevocationChecker checker = checker(30, 200);

        long start = System.nanoTime();
        Verdict verdict = checker.check(UUID.randomUUID(), "tok", exp());
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(verdict).isEqualTo(Verdict.UNAVAILABLE);
        assertThat(elapsedMillis).isLessThan(1200);
    }

    @Test
    @DisplayName("같은 sid 에 대한 동시 요청은 IAM 호출 한 번으로 합쳐진다")
    void concurrentRequestsForSameSidCauseOneCall() throws Exception {
        iam.delay(400);
        SessionRevocationChecker checker = checker(30, 2000);
        UUID sid = UUID.randomUUID();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Verdict>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return checker.check(sid, "tok", exp());
            }));
        }
        start.countDown();
        for (Future<Verdict> f : results) {
            assertThat(f.get()).isEqualTo(Verdict.ACTIVE);
        }
        pool.shutdownNow();

        assertThat(iam.hits()).isEqualTo(1);
    }

    @Test
    @DisplayName("서로 다른 sid 는 각각 한 번씩 조회하고, Authorization 에 같은 토큰을 Bearer 로 전달한다")
    void forwardsBearerTokenPerSid() {
        SessionRevocationChecker checker = checker(30, 1000);
        checker.check(UUID.randomUUID(), "token-a", exp());
        checker.check(UUID.randomUUID(), "token-b", exp());

        assertThat(iam.authorizationHeaders()).containsExactly("Bearer token-a", "Bearer token-b");
    }

    @Test
    @DisplayName("cacheSeconds=0 의 활성 판정은 캐시하지 않는다")
    void zeroLifetimeEntriesAreNotCached() {
        SessionRevocationChecker zeroCache = checker(0, 1000);
        UUID sid = UUID.randomUUID();
        zeroCache.check(sid, "tok", exp());
        zeroCache.check(sid, "tok", exp());
        assertThat(iam.hits()).isEqualTo(2);
        assertThat(zeroCache.cacheSize()).isZero();
    }

    @Test
    @DisplayName("캐시는 최대 항목 수를 넘지 않고, 가득 차면 만료 항목 → 가장 오래된 항목 순으로 비운다")
    void cacheIsBounded() {
        SessionRevocationChecker checker = checker(30, 1000);
        int total = SessionRevocationChecker.MAX_CACHE_ENTRIES + 50;
        UUID first = UUID.randomUUID();
        checker.check(first, "tok", exp());
        for (int i = 1; i < total; i++) {
            checker.check(UUID.randomUUID(), "tok", exp());
        }
        assertThat(checker.cacheSize()).isLessThanOrEqualTo(SessionRevocationChecker.MAX_CACHE_ENTRIES);

        int before = iam.hits();
        checker.check(first, "tok", exp()); // 가장 오래된 항목은 밀려났으므로 다시 조회한다
        assertThat(iam.hits()).isEqualTo(before + 1);

        // 만료된 항목이 먼저 제거된다
        clock.advanceSeconds(31);
        checker.check(UUID.randomUUID(), "tok", exp());
        assertThat(checker.cacheSize()).isLessThanOrEqualTo(SessionRevocationChecker.MAX_CACHE_ENTRIES);
    }

    @Test
    @DisplayName("확인 URL 유도: revocation-url 우선, 없으면 jwks-uri 의 origin + /api/v1/sessions/current, 해석 불가면 null")
    void resolveUrl() {
        assertThat(SessionRevocationChecker.resolveUrl("http://iam:9000/x", "http://a:1/.well-known/jwks.json"))
                .isEqualTo("http://iam:9000/x");
        assertThat(SessionRevocationChecker.resolveUrl("", "http://auth-api:8080/.well-known/jwks.json"))
                .isEqualTo("http://auth-api:8080/api/v1/sessions/current");
        assertThat(SessionRevocationChecker.resolveUrl(null, "https://iam.example.com/.well-known/jwks.json"))
                .isEqualTo("https://iam.example.com/api/v1/sessions/current");
        assertThat(SessionRevocationChecker.resolveUrl("", "")).isNull();
        assertThat(SessionRevocationChecker.resolveUrl("", null)).isNull();
        assertThat(SessionRevocationChecker.resolveUrl("", "not a uri")).isNull();
        assertThat(SessionRevocationChecker.resolveUrl("", "/relative/path")).isNull();
    }
}

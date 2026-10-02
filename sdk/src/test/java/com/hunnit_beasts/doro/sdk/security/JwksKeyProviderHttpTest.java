package com.hunnit_beasts.doro.sdk.security.jwks;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** 실제 HTTP 로 JWKS 를 조회하는 경로를 JDK 내장 HttpServer 로 검증한다. */
class JwksKeyProviderHttpTest {

    private static final long SHORT_COOLDOWN_MILLIS = 50L;
    private static final long SHORT_TTL_MILLIS = 100L;
    private static final long LONG_MILLIS = 600_000L;
    private static final long SLOW_RESPONSE_MILLIS = 1_500L;

    private HttpServer server;
    private String jwksUri;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger hits = new AtomicInteger();
    private volatile long delayMillis = 0L;
    private PublicKey keyA;
    private PublicKey keyB;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyA = gen.generateKeyPair().getPublic();
        keyB = gen.generateKeyPair().getPublic();

        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/jwks.json", exchange -> {
            hits.incrementAndGet();
            try {
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        jwksUri = "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/jwks.json";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static String b64(java.math.BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String jwks(String kid1, PublicKey key1, String kid2, PublicKey key2) {
        StringBuilder sb = new StringBuilder("{\"keys\":[");
        List<String> entries = new java.util.ArrayList<>();
        entries.add(entry(kid1, key1));
        if (kid2 != null) {
            entries.add(entry(kid2, key2));
        }
        sb.append(String.join(",", entries)).append("]}");
        return sb.toString();
    }

    private static String entry(String kid, PublicKey key) {
        RSAPublicKey rsa = (RSAPublicKey) key;
        return "{\"kty\":\"RSA\",\"kid\":\"" + kid + "\",\"n\":\"" + b64(rsa.getModulus())
                + "\",\"e\":\"" + b64(rsa.getPublicExponent()) + "\"}";
    }

    @Test
    @DisplayName("첫 조회: 알 수 없는 kid 는 동기로 JWKS 를 가져와 키를 반환한다")
    void firstFetchIsSynchronous() {
        body.set(jwks("k1", keyA, null, null));
        JwksKeyProvider provider = new JwksKeyProvider(jwksUri, SHORT_COOLDOWN_MILLIS, LONG_MILLIS);

        assertThat(provider.getPublicKey("k1")).isEqualTo(keyA);
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("TTL 갱신: 만료 후 캐시된 키를 즉시 반환하고 백그라운드에서 새 키 집합을 반영한다")
    void ttlRefreshHappensInBackground() throws Exception {
        body.set(jwks("k1", keyA, null, null));
        JwksKeyProvider provider = new JwksKeyProvider(jwksUri, SHORT_COOLDOWN_MILLIS, SHORT_TTL_MILLIS);
        assertThat(provider.getPublicKey("k1")).isEqualTo(keyA);

        body.set(jwks("k1", keyA, "k2", keyB));
        Thread.sleep(SHORT_TTL_MILLIS + SHORT_COOLDOWN_MILLIS + 50);
        assertThat(provider.getPublicKey("k1")).isEqualTo(keyA);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(hits.get()).isGreaterThanOrEqualTo(2));
        await().atMost(Duration.ofSeconds(5)).until(() -> provider.getPublicKey("k2") != null);
    }

    @Test
    @DisplayName("TTL 갱신은 호출 스레드를 막지 않는다 (느린 JWKS 서버에서도 즉시 반환)")
    void asyncRefreshDoesNotBlockCaller() throws Exception {
        body.set(jwks("k1", keyA, null, null));
        JwksKeyProvider provider = new JwksKeyProvider(jwksUri, SHORT_COOLDOWN_MILLIS, SHORT_TTL_MILLIS);
        provider.getPublicKey("k1");

        delayMillis = SLOW_RESPONSE_MILLIS;
        Thread.sleep(SHORT_TTL_MILLIS + SHORT_COOLDOWN_MILLIS + 50);

        long start = System.nanoTime();
        PublicKey result = provider.getPublicKey("k1");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(result).isEqualTo(keyA);
        assertThat(elapsedMillis).isLessThan(SLOW_RESPONSE_MILLIS / 2);
        // 백그라운드 갱신 종료 대기 (다음 테스트/서버 종료와 간섭 방지)
        await().atMost(Duration.ofSeconds(5)).until(() -> hits.get() >= 2);
    }

    @Test
    @DisplayName("TTL 갱신은 동시에 하나만 in-flight 로 실행된다")
    void singleInFlightRefresh() throws Exception {
        body.set(jwks("k1", keyA, null, null));
        JwksKeyProvider provider = new JwksKeyProvider(jwksUri, 0L, SHORT_TTL_MILLIS);
        provider.getPublicKey("k1");
        delayMillis = 600L;
        Thread.sleep(SHORT_TTL_MILLIS + 50);

        for (int i = 0; i < 20; i++) {
            provider.getPublicKey("k1");
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> hits.get() >= 2);
        Thread.sleep(200);
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("성공한 조회에서 JWKS 에서 사라진 kid 는 캐시에서 제거되고 수동 등록 키는 유지된다")
    void evictsRemovedKeysButKeepsManualOnes() {
        body.set(jwks("k1", keyA, "k2", keyB));
        JwksKeyProvider provider = new JwksKeyProvider(jwksUri, SHORT_COOLDOWN_MILLIS, LONG_MILLIS);
        provider.registerKey("manual", keyB);
        provider.refreshKeys();
        assertThat(provider.getPublicKey("k1")).isNotNull();
        assertThat(provider.getPublicKey("k2")).isNotNull();

        body.set(jwks("k2", keyB, null, null));
        provider.refreshKeys();

        assertThat(provider.getPublicKey("k2")).isNotNull();
        assertThat(provider.getPublicKey("manual")).isNotNull();
        // 제거 확인은 쿨다운 재조회를 피하려고 긴 쿨다운 provider 로 한다
        JwksKeyProvider noRefetch = new JwksKeyProvider(jwksUri, LONG_MILLIS, LONG_MILLIS);
        body.set(jwks("k1", keyA, "k2", keyB));
        noRefetch.refreshKeys();
        body.set(jwks("k2", keyB, null, null));
        noRefetch.refreshKeys();
        assertThat(noRefetch.getPublicKey("k1")).isNull();
        assertThat(noRefetch.getPublicKey("k2")).isNotNull();
    }

    @Test
    @DisplayName("조회 실패(HTTP 500) 또는 빈 JWKS 는 기존 캐시 키를 지우지 않는다")
    void failureAndEmptyJwksKeepStaleKeys() {
        body.set(jwks("k1", keyA, null, null));
        JwksKeyProvider provider = new JwksKeyProvider(jwksUri, LONG_MILLIS, LONG_MILLIS);
        provider.refreshKeys();

        status.set(500);
        body.set("boom");
        provider.refreshKeys();
        assertThat(provider.getPublicKey("k1")).isEqualTo(keyA);

        status.set(200);
        body.set("{\"keys\":[]}");
        provider.refreshKeys();
        assertThat(provider.getPublicKey("k1")).isEqualTo(keyA);

        body.set("not json");
        provider.refreshKeys();
        assertThat(provider.getPublicKey("k1")).isEqualTo(keyA);
    }

    @Test
    @DisplayName("prefetchAsync 는 호출을 막지 않고 백그라운드에서 키를 적재하며, 실패해도 예외가 없다")
    void prefetchIsBackgroundAndBestEffort() {
        delayMillis = 500L;
        body.set(jwks("k1", keyA, null, null));
        JwksKeyProvider provider = new JwksKeyProvider(jwksUri, SHORT_COOLDOWN_MILLIS, LONG_MILLIS);

        long start = System.nanoTime();
        provider.prefetchAsync();
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(250L);
        await().atMost(Duration.ofSeconds(5)).until(() -> provider.getPublicKey("k1") != null);

        // 연결 불가 URI 에서도 예외 없이 끝난다
        JwksKeyProvider unreachable = new JwksKeyProvider("http://127.0.0.1:1/jwks.json", SHORT_COOLDOWN_MILLIS, LONG_MILLIS);
        unreachable.prefetchAsync();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(unreachable.getPublicKey("none")).isNull());
    }
}

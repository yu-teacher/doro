package com.hunnit_beasts.doro.sdk.security.revocation;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * IAM 의 {@code GET /api/v1/sessions/current} 로 세션이 아직 유효한지 확인한다.
 *
 * <ul>
 *   <li>204 → {@link Verdict#ACTIVE}: {@code cacheSeconds} 동안 sid 별로 재사용한다.</li>
 *   <li>401/403 → {@link Verdict#REVOKED}: 토큰의 exp 까지 다시 묻지 않는다.</li>
 *   <li>그 외(연결 실패, 타임아웃, 5xx, 예상 밖 응답) → {@link Verdict#UNAVAILABLE}: 캐시하지 않는다.</li>
 * </ul>
 *
 * 캐시 키는 항상 sid(UUID)이며 원본 토큰은 키로 쓰거나 로그에 남기지 않는다. 같은 sid 에 대한 동시 조회는
 * 하나의 IAM 호출로 합쳐진다(single-flight).
 */
@Slf4j
public class SessionRevocationChecker {

    /** 조회 결과. UNAVAILABLE 은 IAM 이 판정을 내리지 못한 경우(fail-open/closed 정책은 호출 측이 결정). */
    public enum Verdict { ACTIVE, REVOKED, UNAVAILABLE }

    /** 캐시 최대 항목 수. 가득 차면 만료 항목을 먼저, 그래도 부족하면 가장 오래된 항목을 제거한다. */
    static final int MAX_CACHE_ENTRIES = 10_000;
    /** IAM 장애 WARN 로그의 최소 간격(밀리초) */
    static final long UNAVAILABLE_WARN_INTERVAL_MILLIS = 60_000L;
    /** single-flight 대기자가 리더의 응답을 기다리는 최대 시간에 더하는 여유(밀리초) */
    private static final long WAIT_MARGIN_MILLIS = 500L;
    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String MDC_TRACE_ID = "traceId";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final int HTTP_NO_CONTENT = 204;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;

    private record CacheEntry(Verdict verdict, long validUntilMillis, long storedAtMillis, long sequence) {
    }

    private final URI url;
    private final long activeCacheMillis;
    private final long waitMillis;
    /** IAM 이 판정을 못 내린 뒤 다시 호출하지 않는 시간(밀리초). 0 이하면 매번 시도한다. */
    private final long failureBackoffMillis;
    private final Clock clock;
    private final RestClient restClient;
    private final Map<UUID, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Verdict>> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final Object cacheWriteLock = new Object();
    private volatile long lastUnavailableWarnMillis = Long.MIN_VALUE;
    /** 이 시각 전까지는 IAM 을 호출하지 않는다(장애가 모든 요청의 타임아웃 지연으로 번지는 것을 막는다). */
    private volatile long unavailableUntilMillis = Long.MIN_VALUE;

    public SessionRevocationChecker(String url, long cacheSeconds, int timeoutMillis) {
        this(url, cacheSeconds, timeoutMillis, Clock.systemUTC());
    }

    /** 테스트에서 시계를 주입하기 위한 생성자 */
    public SessionRevocationChecker(String url, long cacheSeconds, int timeoutMillis, Clock clock) {
        this(url, cacheSeconds, timeoutMillis, 0L, clock);
    }

    public SessionRevocationChecker(String url, long cacheSeconds, int timeoutMillis, long failureBackoffMillis) {
        this(url, cacheSeconds, timeoutMillis, failureBackoffMillis, Clock.systemUTC());
    }

    public SessionRevocationChecker(String url, long cacheSeconds, int timeoutMillis, long failureBackoffMillis, Clock clock) {
        this.failureBackoffMillis = Math.max(0L, failureBackoffMillis);
        this.url = URI.create(url);
        this.activeCacheMillis = Math.max(0L, cacheSeconds) * 1000L;
        this.waitMillis = 2L * timeoutMillis + WAIT_MARGIN_MILLIS;
        this.clock = clock;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMillis);
        requestFactory.setReadTimeout(timeoutMillis);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    /**
     * 세션 sid 의 폐기 여부를 확인한다.
     *
     * @param token           IAM 에 그대로 전달할 액세스 토큰(Authorization: Bearer)
     * @param tokenExpiryMillis 토큰 exp(epoch millis). 폐기 판정을 이 시각까지 캐시한다.
     */
    public Verdict check(UUID sid, String token, long tokenExpiryMillis) {
        Verdict cached = fromCache(sid);
        if (cached != null) {
            return cached;
        }

        // IAM 이 직전에 판정을 못 내렸다면 백오프가 끝날 때까지 호출하지 않고 바로 UNAVAILABLE (fail-open/closed 는 호출 측 정책)
        if (clock.millis() < unavailableUntilMillis) {
            return Verdict.UNAVAILABLE;
        }

        CompletableFuture<Verdict> mine = new CompletableFuture<>();
        CompletableFuture<Verdict> existing = inFlight.putIfAbsent(sid, mine);
        if (existing != null) {
            return await(existing);
        }

        Verdict verdict = Verdict.UNAVAILABLE;
        try {
            // 다른 스레드가 방금 끝낸 조회 결과가 있으면 재사용한다.
            Verdict again = fromCache(sid);
            verdict = again != null ? again : fetchAndCache(sid, token, tokenExpiryMillis);
        } catch (RuntimeException e) {
            log.warn("Session revocation check failed unexpectedly: sid={}, error={}", sid, e.toString());
        } finally {
            inFlight.remove(sid);
            mine.complete(verdict);
        }
        return verdict;
    }

    private Verdict await(CompletableFuture<Verdict> future) {
        try {
            return future.get(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Verdict.UNAVAILABLE;
        } catch (ExecutionException | TimeoutException e) {
            return Verdict.UNAVAILABLE;
        }
    }

    private Verdict fromCache(UUID sid) {
        CacheEntry entry = cache.get(sid);
        if (entry == null) {
            return null;
        }
        if (entry.validUntilMillis() > clock.millis()) {
            return entry.verdict();
        }
        cache.remove(sid, entry);
        return null;
    }

    private Verdict fetchAndCache(UUID sid, String token, long tokenExpiryMillis) {
        int status;
        try {
            status = restClient.get()
                    .uri(url)
                    .headers(headers -> {
                        headers.set(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + token);
                        String traceId = MDC.get(MDC_TRACE_ID);
                        if (traceId != null) {
                            headers.set(TRACE_ID_HEADER, traceId);
                        }
                    })
                    .exchange((request, response) -> response.getStatusCode().value());
        } catch (RuntimeException e) {
            warnUnavailable("IAM unreachable (" + e.getClass().getSimpleName() + ")");
            startBackoff();
            return Verdict.UNAVAILABLE;
        }

        long now = clock.millis();
        if (status == HTTP_NO_CONTENT || status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN) {
            unavailableUntilMillis = Long.MIN_VALUE; // IAM 이 정상적으로 판정을 내렸으므로 백오프 해제
        }
        if (status == HTTP_NO_CONTENT) {
            put(sid, new CacheEntry(Verdict.ACTIVE, now + activeCacheMillis, now, sequence.incrementAndGet()));
            return Verdict.ACTIVE;
        }
        if (status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN) {
            put(sid, new CacheEntry(Verdict.REVOKED, tokenExpiryMillis, now, sequence.incrementAndGet()));
            return Verdict.REVOKED;
        }
        warnUnavailable("IAM answered unexpected status " + status);
        startBackoff();
        return Verdict.UNAVAILABLE;
    }

    private void startBackoff() {
        if (failureBackoffMillis > 0) {
            unavailableUntilMillis = clock.millis() + failureBackoffMillis;
        }
    }

    private void put(UUID sid, CacheEntry entry) {
        if (entry.validUntilMillis() <= entry.storedAtMillis()) {
            return; // 캐시 수명이 0 이하(예: 이미 만료된 토큰, cacheSeconds=0)이면 저장하지 않는다.
        }
        synchronized (cacheWriteLock) {
            if (cache.size() >= MAX_CACHE_ENTRIES && !cache.containsKey(sid)) {
                evict(entry.storedAtMillis());
            }
            cache.put(sid, entry);
        }
    }

    /** 만료된 항목을 먼저 제거하고, 그래도 가득 차 있으면 가장 먼저 저장된 항목(저장 순번 기준)을 하나 제거한다. */
    private void evict(long now) {
        cache.values().removeIf(e -> e.validUntilMillis() <= now);
        if (cache.size() < MAX_CACHE_ENTRIES) {
            return;
        }
        cache.entrySet().stream()
                .min((a, b) -> Long.compare(a.getValue().sequence(), b.getValue().sequence()))
                .ifPresent(oldest -> cache.remove(oldest.getKey()));
    }

    private void warnUnavailable(String reason) {
        long now = clock.millis();
        long last = lastUnavailableWarnMillis;
        if (last == Long.MIN_VALUE || now - last >= UNAVAILABLE_WARN_INTERVAL_MILLIS) {
            lastUnavailableWarnMillis = now;
            log.warn("Session revocation check unavailable: {} (url={}); further occurrences are logged at most once per {}s",
                    reason, url, UNAVAILABLE_WARN_INTERVAL_MILLIS / 1000);
        }
    }

    /** 현재 캐시 항목 수(테스트/진단용) */
    int cacheSize() {
        return cache.size();
    }

    /**
     * 설정에서 IAM 확인 URL 을 결정한다. revocationUrl 이 있으면 그대로, 없으면 jwksUri 의
     * scheme://host[:port] 에 {@code /api/v1/sessions/current} 를 붙인다. 결정할 수 없으면 null.
     */
    public static String resolveUrl(String revocationUrl, String jwksUri) {
        if (revocationUrl != null && !revocationUrl.isBlank()) {
            return revocationUrl.trim();
        }
        if (jwksUri == null || jwksUri.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(jwksUri.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            String origin = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() >= 0 ? ":" + uri.getPort() : "");
            return origin + CURRENT_SESSION_PATH;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static final String CURRENT_SESSION_PATH = "/api/v1/sessions/current";
}

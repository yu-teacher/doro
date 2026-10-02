package com.hunnit_beasts.doro.sdk.security.jwks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class JwksKeyProvider {

    private final String jwksUri;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Map<String, PublicKey> keyCache = new ConcurrentHashMap<>();
    /** registerKey 로 수동 등록한 kid. JWKS 조회 결과에 없어도 evict 하지 않는다. */
    private final Set<String> manuallyRegisteredKids = ConcurrentHashMap.newKeySet();
    /** TTL 만료로 인한 백그라운드 갱신이 동시에 둘 이상 돌지 않도록 하는 in-flight 가드 */
    private final AtomicBoolean asyncRefreshInFlight = new AtomicBoolean(false);
    private final long refreshCooldownMillis;
    private final long keyTtlMillis;
    private volatile long lastRefreshAttemptMillis = 0L;
    private volatile long lastSuccessfulRefreshMillis = 0L;

    /** 캐시 미스/만료로 인한 JWKS 재조회 사이의 최소 간격 (임의 kid 요청에 의한 증폭 방지) */
    private static final long DEFAULT_REFRESH_COOLDOWN_MILLIS = 30_000L;
    /** 캐시된 키를 이 시간이 지나면 다음 조회 때 갱신한다 (같은 kid 로 키가 교체된 경우 대비) */
    private static final long DEFAULT_KEY_TTL_MILLIS = 600_000L;
    private static final int HTTP_TIMEOUT_MILLIS = 3_000;
    private static final String REFRESH_THREAD_NAME = "doro-jwks-refresh";

    public JwksKeyProvider(String jwksUri) {
        this(jwksUri, DEFAULT_REFRESH_COOLDOWN_MILLIS, DEFAULT_KEY_TTL_MILLIS);
    }

    /** 테스트에서 TTL/쿨다운을 짧게 주입하기 위한 생성자 */
    JwksKeyProvider(String jwksUri, long refreshCooldownMillis, long keyTtlMillis) {
        this.jwksUri = jwksUri;
        this.refreshCooldownMillis = refreshCooldownMillis;
        this.keyTtlMillis = keyTtlMillis;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(HTTP_TIMEOUT_MILLIS);
        requestFactory.setReadTimeout(HTTP_TIMEOUT_MILLIS);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
        this.objectMapper = new ObjectMapper();
    }

    public PublicKey getPublicKey(String kid) {
        PublicKey cached = keyCache.get(kid);
        long now = System.currentTimeMillis();
        if (cached != null) {
            // TTL 만료 갱신은 요청 스레드를 막지 않는다: 캐시된 키를 즉시 반환하고 백그라운드에서 갱신
            if (now - lastSuccessfulRefreshMillis > keyTtlMillis && cooldownElapsed(now)) {
                refreshKeysAsync();
            }
            return cached;
        }

        // 알 수 없는 kid 는 새 키일 수 있으므로 동기 조회한다 (쿨다운으로 증폭 방지)
        if (cooldownElapsed(now)) {
            refreshKeys();
        }
        return keyCache.get(kid);
    }

    private boolean cooldownElapsed(long now) {
        return jwksUri != null && !jwksUri.isBlank() && now - lastRefreshAttemptMillis >= refreshCooldownMillis;
    }

    public void registerKey(String kid, PublicKey publicKey) {
        manuallyRegisteredKids.add(kid);
        keyCache.put(kid, publicKey);
    }

    public synchronized void refreshKeys() {
        fetchAndApply();
    }

    /**
     * 기동 시 best-effort 사전 조회. 백그라운드 스레드에서 수행하며 실패해도 경고 로그만 남기고
     * 기동을 막지 않는다. 실패 시 첫 요청의 동기 조회가 쿨다운에 막히지 않도록 시도 시각을 되돌린다.
     */
    public void prefetchAsync() {
        if (jwksUri == null || jwksUri.isBlank()) {
            return;
        }
        startRefreshThread(() -> {
            synchronized (this) {
                if (!fetchAndApply()) {
                    lastRefreshAttemptMillis = 0L;
                }
            }
        });
    }

    /** 단일 in-flight 가드로 백그라운드 갱신을 시작한다. 이미 진행 중이면 아무 것도 하지 않는다. */
    private void refreshKeysAsync() {
        startRefreshThread(this::refreshKeys);
    }

    private void startRefreshThread(Runnable task) {
        if (!asyncRefreshInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            Thread.ofPlatform().daemon().name(REFRESH_THREAD_NAME).start(() -> {
                try {
                    task.run();
                } finally {
                    asyncRefreshInFlight.set(false);
                }
            });
        } catch (RuntimeException e) {
            asyncRefreshInFlight.set(false);
            log.warn("Failed to start JWKS refresh thread: {}", e.getMessage());
        }
    }

    /** @return 키를 1개 이상 받아 캐시에 반영했으면 true */
    private boolean fetchAndApply() {
        if (jwksUri == null || jwksUri.isBlank()) {
            return false;
        }

        lastRefreshAttemptMillis = System.currentTimeMillis();
        try {
            String jwksJson = restClient.get()
                    .uri(jwksUri)
                    .retrieve()
                    .body(String.class);

            Map<String, PublicKey> fetched = jwksJson != null ? parseJwks(jwksJson) : Map.of();
            if (fetched.isEmpty()) {
                // 비어 있거나 파싱 실패한 JWKS 는 장애로 간주하여 기존 키를 유지한다
                log.warn("JWKS from {} contained no usable keys; keeping cached keys", jwksUri);
                return false;
            }
            keyCache.putAll(fetched);
            evictRemovedKeys(fetched.keySet());
            lastSuccessfulRefreshMillis = System.currentTimeMillis();
            log.info("Successfully refreshed JWKS keys from {}", jwksUri);
            return true;
        } catch (Exception e) {
            log.warn("Failed to fetch JWKS from {}: {}", jwksUri, e.getMessage());
            return false;
        }
    }

    /** JWKS 에서 사라진 kid 를 캐시에서 제거한다 (수동 등록 키는 유지). */
    private void evictRemovedKeys(Set<String> currentKids) {
        keyCache.keySet().removeIf(kid -> {
            boolean stale = !currentKids.contains(kid) && !manuallyRegisteredKids.contains(kid);
            if (stale) {
                log.info("Evicted JWKS key no longer published: kid={}", kid);
            }
            return stale;
        });
    }

    /** JWKS JSON 을 파싱해 캐시에 추가한다 (제거는 하지 않음). */
    public void parseAndCacheJwks(String jwksJson) {
        keyCache.putAll(parseJwks(jwksJson));
    }

    private Map<String, PublicKey> parseJwks(String jwksJson) {
        Map<String, PublicKey> result = new HashMap<>();
        try {
            JsonNode root = objectMapper.readTree(jwksJson);
            JsonNode keys = root.get("keys");
            if (keys != null && keys.isArray()) {
                for (JsonNode keyNode : keys) {
                    String kty = keyNode.path("kty").asText();
                    String kid = keyNode.path("kid").asText();
                    String n = keyNode.path("n").asText();
                    String e = keyNode.path("e").asText();

                    if ("RSA".equalsIgnoreCase(kty) && !n.isEmpty() && !e.isEmpty()) {
                        result.put(kid, constructRsaPublicKey(n, e));
                        log.debug("Parsed RSA Public Key with kid={}", kid);
                    }
                }
            }
        } catch (Exception ex) {
            log.error("Failed to parse JWKS JSON: {}", ex.getMessage(), ex);
            return Map.of();
        }
        return result;
    }

    private PublicKey constructRsaPublicKey(String nStr, String eStr) throws Exception {
        byte[] nBytes = Base64.getUrlDecoder().decode(nStr);
        byte[] eBytes = Base64.getUrlDecoder().decode(eStr);

        BigInteger modulus = new BigInteger(1, nBytes);
        BigInteger publicExponent = new BigInteger(1, eBytes);

        RSAPublicKeySpec spec = new RSAPublicKeySpec(modulus, publicExponent);
        KeyFactory factory = KeyFactory.getInstance("RSA");
        return factory.generatePublic(spec);
    }
}

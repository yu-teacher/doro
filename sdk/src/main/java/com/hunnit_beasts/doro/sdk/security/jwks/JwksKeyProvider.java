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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class JwksKeyProvider {

    private final String jwksUri;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Map<String, PublicKey> keyCache = new ConcurrentHashMap<>();
    private volatile long lastRefreshAttemptMillis = 0L;
    private volatile long lastSuccessfulRefreshMillis = 0L;

    /** 캐시 미스/만료로 인한 JWKS 재조회 사이의 최소 간격 (임의 kid 요청에 의한 증폭 방지) */
    private static final long REFRESH_COOLDOWN_MILLIS = 30_000L;
    /** 캐시된 키를 이 시간이 지나면 다음 조회 때 갱신한다 (같은 kid 로 키가 교체된 경우 대비) */
    private static final long KEY_TTL_MILLIS = 600_000L;
    private static final int HTTP_TIMEOUT_MILLIS = 3_000;

    public JwksKeyProvider(String jwksUri) {
        this.jwksUri = jwksUri;
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
            if (now - lastSuccessfulRefreshMillis > KEY_TTL_MILLIS && cooldownElapsed(now)) {
                refreshKeys();
                PublicKey refreshed = keyCache.get(kid);
                return refreshed != null ? refreshed : cached;
            }
            return cached;
        }

        if (cooldownElapsed(now)) {
            refreshKeys();
        }
        return keyCache.get(kid);
    }

    private boolean cooldownElapsed(long now) {
        return jwksUri != null && !jwksUri.isBlank() && now - lastRefreshAttemptMillis >= REFRESH_COOLDOWN_MILLIS;
    }

    public void registerKey(String kid, PublicKey publicKey) {
        keyCache.put(kid, publicKey);
    }

    public synchronized void refreshKeys() {
        if (jwksUri == null || jwksUri.isBlank()) {
            return;
        }

        lastRefreshAttemptMillis = System.currentTimeMillis();
        try {
            String jwksJson = restClient.get()
                    .uri(jwksUri)
                    .retrieve()
                    .body(String.class);

            if (jwksJson != null) {
                parseAndCacheJwks(jwksJson);
                lastSuccessfulRefreshMillis = System.currentTimeMillis();
                log.info("Successfully refreshed JWKS keys from {}", jwksUri);
            }
        } catch (Exception e) {
            log.warn("Failed to fetch JWKS from {}: {}", jwksUri, e.getMessage());
        }
    }

    public void parseAndCacheJwks(String jwksJson) {
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
                        PublicKey publicKey = constructRsaPublicKey(n, e);
                        keyCache.put(kid, publicKey);
                        log.debug("Cached RSA Public Key with kid={}", kid);
                    }
                }
            }
        } catch (Exception ex) {
            log.error("Failed to parse JWKS JSON: {}", ex.getMessage(), ex);
        }
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

package com.hunnit_beasts.doro.sdk.security.jwks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
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

    public JwksKeyProvider(String jwksUri) {
        this.jwksUri = jwksUri;
        this.restClient = RestClient.builder().build();
        this.objectMapper = new ObjectMapper();
    }

    public PublicKey getPublicKey(String kid) {
        PublicKey cached = keyCache.get(kid);
        if (cached != null) {
            return cached;
        }

        refreshKeys();
        return keyCache.get(kid);
    }

    public void registerKey(String kid, PublicKey publicKey) {
        keyCache.put(kid, publicKey);
    }

    public synchronized void refreshKeys() {
        if (jwksUri == null || jwksUri.isBlank()) {
            return;
        }

        try {
            String jwksJson = restClient.get()
                    .uri(jwksUri)
                    .retrieve()
                    .body(String.class);

            if (jwksJson != null) {
                parseAndCacheJwks(jwksJson);
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

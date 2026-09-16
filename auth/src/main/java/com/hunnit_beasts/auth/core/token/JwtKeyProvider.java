package com.hunnit_beasts.auth.core.token;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Getter
@Component
public class JwtKeyProvider {

    private static final String REDIS_RSA_PRIV_KEY = "doro:iam:jwt:keypair:private";
    private static final String REDIS_RSA_PUB_KEY = "doro:iam:jwt:keypair:public";

    private final StringRedisTemplate redisTemplate;

    public JwtKeyProvider() {
        this.redisTemplate = null;
    }

    public JwtKeyProvider(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Value("${doro.iam.jwt.key-id:doro-iam-key-2026-v1}")
    private String keyId;

    @Value("${doro.iam.jwt.private-key-pem:}")
    private String privateKeyPem;

    @Value("${doro.iam.jwt.public-key-pem:}")
    private String publicKeyPem;

    private KeyPair keyPair;

    @PostConstruct
    public void init() {
        // 1. 설정 파일에 직접 주입된 키가 있는 경우
        if (privateKeyPem != null && !privateKeyPem.isBlank() && publicKeyPem != null && !publicKeyPem.isBlank()) {
            try {
                this.keyPair = loadKeyPairFromPem(privateKeyPem, publicKeyPem);
                log.info("Successfully loaded JWT RSA KeyPair from external secure configuration. KeyId={}", keyId);
                return;
            } catch (Exception e) {
                log.warn("Failed to load external RSA keys: {}", e.getMessage());
            }
        }

        // 2. Redis 영속 스토어에 보관된 키가 있는지 확인 (컨테이너 재시작 시 토큰 무효화 방지)
        if (redisTemplate != null) {
            try {
                String cachedPriv = redisTemplate.opsForValue().get(REDIS_RSA_PRIV_KEY);
                String cachedPub = redisTemplate.opsForValue().get(REDIS_RSA_PUB_KEY);
                if (cachedPriv != null && cachedPub != null) {
                    this.keyPair = loadKeyPairFromPem(cachedPriv, cachedPub);
                    log.info("Successfully restored persistent JWT RSA KeyPair from Redis. KeyId={}", keyId);
                    return;
                }
            } catch (Exception e) {
                log.warn("Could not check Redis for JWT keys: {}", e.getMessage());
            }
        }

        // 3. 최초 기동 시 신규 RSA-2048 키페어 생성 후 Redis에 영구 보존
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048);
            this.keyPair = keyGen.generateKeyPair();

            String privBase64 = Base64.getEncoder().encodeToString(this.keyPair.getPrivate().getEncoded());
            String pubBase64 = Base64.getEncoder().encodeToString(this.keyPair.getPublic().getEncoded());

            if (redisTemplate != null) {
                try {
                    redisTemplate.opsForValue().set(REDIS_RSA_PRIV_KEY, privBase64);
                    redisTemplate.opsForValue().set(REDIS_RSA_PUB_KEY, pubBase64);
                    log.info("Persisted new RSA-2048 KeyPair to Redis store. KeyId={}", keyId);
                } catch (Exception e) {
                    log.warn("Failed to save JWT keys to Redis: {}", e.getMessage());
                }
            }

            log.info("Generated RSA-2048 KeyPair for JWT. KeyId={}", keyId);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Failed to initialize RSA KeyPair for JWT", e);
        }
    }

    public RSAPublicKey getPublicKey() {
        return (RSAPublicKey) keyPair.getPublic();
    }

    public RSAPrivateKey getPrivateKey() {
        return (RSAPrivateKey) keyPair.getPrivate();
    }

    /**
     * RFC 7517 표준에 맞춘 JWKS (JSON Web Key Set) 맵 반환
     */
    public Map<String, Object> getJwks() {
        RSAPublicKey publicKey = getPublicKey();
        Base64.Encoder urlEncoder = Base64.getUrlEncoder().withoutPadding();

        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "RSA");
        jwk.put("use", "sig");
        jwk.put("alg", "RS256");
        jwk.put("kid", keyId);
        jwk.put("n", urlEncoder.encodeToString(publicKey.getModulus().toByteArray()));
        jwk.put("e", urlEncoder.encodeToString(publicKey.getPublicExponent().toByteArray()));

        return Collections.singletonMap("keys", Collections.singletonList(jwk));
    }

    private KeyPair loadKeyPairFromPem(String privatePem, String publicPem) throws Exception {
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");

        String cleanPriv = privatePem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] privBytes = Base64.getDecoder().decode(cleanPriv);
        RSAPrivateKey privKey = (RSAPrivateKey) keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privBytes));

        String cleanPub = publicPem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] pubBytes = Base64.getDecoder().decode(cleanPub);
        RSAPublicKey pubKey = (RSAPublicKey) keyFactory.generatePublic(new X509EncodedKeySpec(pubBytes));

        return new KeyPair(pubKey, privKey);
    }
}

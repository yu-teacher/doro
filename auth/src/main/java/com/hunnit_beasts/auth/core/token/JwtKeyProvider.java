package com.hunnit_beasts.auth.core.token;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class JwtKeyProvider {

    private static final String REDIS_RSA_PRIV_KEY = "doro:iam:jwt:keypair:private";
    private static final String REDIS_RSA_PUB_KEY = "doro:iam:jwt:keypair:public";

    /** Redis 에 암호화되어 저장된 개인키의 접두사. 이 접두사가 없으면 레거시 평문(Base64 PKCS#8)이다. */
    static final String ENCRYPTED_PREFIX = "enc:v1:";

    private static final int RSA_KEY_BITS = 2048;
    private static final int KDF_SALT_BYTES = 16;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int AES_KEY_BITS = 256;
    private static final int PBKDF2_ITERATIONS = 210_000;
    private static final String KDF_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";

    private final StringRedisTemplate redisTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    public JwtKeyProvider() {
        this(null);
    }

    @Autowired(required = false)
    public JwtKeyProvider(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Getter
    @Value("${doro.iam.jwt.key-id:doro-iam-key-2026-v1}")
    private String keyId;

    @Value("${doro.iam.jwt.private-key-pem:}")
    private String privateKeyPem;

    @Value("${doro.iam.jwt.public-key-pem:}")
    private String publicKeyPem;

    /** Redis 에 보관하는 개인키의 at-rest 암호화 시크릿. 비어 있으면 평문으로 보관한다(기존 동작). */
    @Value("${doro.iam.jwt.redis-key-encryption-secret:}")
    private String redisKeyEncryptionSecret;

    /** 키 로테이션 중 이전 키의 kid. 이전 공개키(PEM)와 함께 지정하면 JWKS 에 함께 게시하고 검증에도 사용한다. */
    @Value("${doro.iam.jwt.previous-key-id:}")
    private String previousKeyId;

    @Value("${doro.iam.jwt.previous-public-key-pem:}")
    private String previousPublicKeyPem;

    private KeyPair keyPair;
    private RSAPublicKey previousPublicKey;

    @PostConstruct
    public void init() {
        loadCurrentKeyPair();
        loadPreviousPublicKey();
    }

    private void loadCurrentKeyPair() {
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
            String cachedPriv = null;
            String cachedPub = null;
            try {
                cachedPriv = redisTemplate.opsForValue().get(REDIS_RSA_PRIV_KEY);
                cachedPub = redisTemplate.opsForValue().get(REDIS_RSA_PUB_KEY);
            } catch (Exception e) {
                log.warn("Could not check Redis for JWT keys: {}", e.getMessage());
            }
            if (cachedPriv != null && cachedPub != null) {
                if (restoreKeyPairFromRedis(cachedPriv, cachedPub)) {
                    return;
                }
            }
        }

        // 3. 최초 기동 시 신규 RSA-2048 키페어 생성 후 Redis에 영구 보존
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(RSA_KEY_BITS);
            this.keyPair = keyGen.generateKeyPair();

            if (redisTemplate != null) {
                try {
                    storeKeyPairInRedis(this.keyPair);
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

    /**
     * Redis 에서 읽은 키를 복원한다. 암호화된 값은 복호화에 실패해도 새 키를 생성해 덮어쓰지 않고 기동을 중단한다.
     *
     * @return 복원에 성공하면 true, 손상된 레거시 평문 값이라 새로 생성해야 하면 false
     */
    private boolean restoreKeyPairFromRedis(String storedPriv, String storedPub) {
        boolean secretConfigured = hasEncryptionSecret();

        if (storedPriv.startsWith(ENCRYPTED_PREFIX)) {
            if (!secretConfigured) {
                log.error("The JWT private key stored in Redis is encrypted ({}) but doro.iam.jwt.redis-key-encryption-secret "
                        + "(DORO_IAM_JWT_KEY_ENCRYPTION_SECRET) is not set. Refusing to start so the existing key is not overwritten.",
                        ENCRYPTED_PREFIX);
                throw new IllegalStateException("Encrypted JWT private key found in Redis but no encryption secret is configured");
            }
            try {
                String plainPriv = decrypt(storedPriv);
                this.keyPair = loadKeyPairFromPem(plainPriv, storedPub);
                log.info("Successfully restored encrypted persistent JWT RSA KeyPair from Redis. KeyId={}", keyId);
                return true;
            } catch (Exception e) {
                log.error("Failed to decrypt/restore the JWT private key stored in Redis. The encryption secret is probably wrong "
                        + "or the stored value is corrupted. Refusing to start so the existing key is not overwritten.", e);
                throw new IllegalStateException("Could not decrypt the JWT private key stored in Redis", e);
            }
        }

        // 레거시 평문 형식
        try {
            this.keyPair = loadKeyPairFromPem(storedPriv, storedPub);
        } catch (Exception e) {
            log.warn("Could not parse the JWT keys stored in Redis, generating a new key pair: {}", e.getMessage());
            return false;
        }
        log.info("Successfully restored persistent JWT RSA KeyPair from Redis. KeyId={}", keyId);

        if (secretConfigured) {
            try {
                storeKeyPairInRedis(this.keyPair);
                log.info("Migrated the plaintext JWT private key in Redis to the encrypted format ({}).", ENCRYPTED_PREFIX);
            } catch (Exception e) {
                log.warn("Could not re-store the JWT private key in encrypted form: {}", e.getMessage());
            }
        } else {
            logPlaintextStorageWarning();
        }
        return true;
    }

    private void storeKeyPairInRedis(KeyPair pair) {
        String privBase64 = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        String pubBase64 = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());

        if (hasEncryptionSecret()) {
            redisTemplate.opsForValue().set(REDIS_RSA_PRIV_KEY, encrypt(privBase64));
        } else {
            redisTemplate.opsForValue().set(REDIS_RSA_PRIV_KEY, privBase64);
            logPlaintextStorageWarning();
        }
        redisTemplate.opsForValue().set(REDIS_RSA_PUB_KEY, pubBase64);
    }

    private void logPlaintextStorageWarning() {
        log.error("SECURITY: the JWT private key is stored in PLAINTEXT in Redis. Set doro.iam.jwt.redis-key-encryption-secret "
                + "(env DORO_IAM_JWT_KEY_ENCRYPTION_SECRET) to a long random secret to encrypt it at rest (AES-256-GCM); "
                + "the existing plaintext key is migrated automatically on the next start. "
                + "Alternatively inject the key pair via doro.iam.jwt.private-key-pem / public-key-pem.");
    }

    private boolean hasEncryptionSecret() {
        return redisKeyEncryptionSecret != null && !redisKeyEncryptionSecret.isBlank();
    }

    private void loadPreviousPublicKey() {
        boolean hasPem = previousPublicKeyPem != null && !previousPublicKeyPem.isBlank();
        boolean hasKid = previousKeyId != null && !previousKeyId.isBlank();

        if (!hasPem) {
            if (hasKid) {
                log.warn("doro.iam.jwt.previous-key-id is set but previous-public-key-pem is empty; ignoring the previous key.");
            }
            this.previousPublicKey = null;
            return;
        }
        if (!hasKid) {
            throw new IllegalStateException("doro.iam.jwt.previous-public-key-pem requires doro.iam.jwt.previous-key-id");
        }
        if (previousKeyId.trim().equals(keyId)) {
            throw new IllegalStateException("doro.iam.jwt.previous-key-id must differ from doro.iam.jwt.key-id ("
                    + keyId + ") so tokens can be matched to the right key");
        }
        try {
            this.previousPublicKey = parsePublicKey(previousPublicKeyPem);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse doro.iam.jwt.previous-public-key-pem", e);
        }
        log.info("Loaded previous JWT public key for rotation overlap. PreviousKeyId={}", previousKeyId.trim());
    }

    public RSAPublicKey getPublicKey() {
        return (RSAPublicKey) keyPair.getPublic();
    }

    public RSAPrivateKey getPrivateKey() {
        return (RSAPrivateKey) keyPair.getPrivate();
    }

    /**
     * 토큰 헤더의 kid 에 맞는 검증용 공개키를 돌려준다. 이전 키의 kid 이면 이전 공개키를,
     * 그 외(kid 없음/알 수 없음 포함)에는 기존과 동일하게 현재 키를 사용한다.
     */
    public RSAPublicKey resolveVerificationKey(String kid) {
        if (previousPublicKey != null && kid != null && kid.equals(previousKeyId.trim())) {
            return previousPublicKey;
        }
        return getPublicKey();
    }

    /**
     * RFC 7517 표준에 맞춘 JWKS (JSON Web Key Set) 맵 반환. 현재 키가 먼저, 이전 키(있다면)가 뒤따른다.
     */
    public Map<String, Object> getJwks() {
        List<Map<String, Object>> keys = new ArrayList<>();
        keys.add(toJwk(keyId, getPublicKey()));
        if (previousPublicKey != null) {
            keys.add(toJwk(previousKeyId.trim(), previousPublicKey));
        }
        Map<String, Object> jwks = new LinkedHashMap<>();
        jwks.put("keys", keys);
        return jwks;
    }

    private Map<String, Object> toJwk(String kid, RSAPublicKey publicKey) {
        Base64.Encoder urlEncoder = Base64.getUrlEncoder().withoutPadding();
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "RSA");
        jwk.put("use", "sig");
        jwk.put("alg", "RS256");
        jwk.put("kid", kid);
        jwk.put("n", urlEncoder.encodeToString(toUnsignedBytes(publicKey.getModulus())));
        jwk.put("e", urlEncoder.encodeToString(toUnsignedBytes(publicKey.getPublicExponent())));
        return jwk;
    }

    /** RFC 7518 §2: 부호 비트용 선행 0x00 바이트를 제거한 최소 길이의 unsigned big-endian 표현. */
    static byte[] toUnsignedBytes(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            return Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return bytes;
    }

    // ---- at-rest 암호화: "enc:v1:" + Base64(salt | iv | AES-256-GCM(ciphertext+tag)) ----

    private String encrypt(String plaintext) {
        try {
            byte[] salt = new byte[KDF_SALT_BYTES];
            byte[] iv = new byte[GCM_IV_BYTES];
            secureRandom.nextBytes(salt);
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(salt), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] payload = new byte[salt.length + iv.length + cipherText.length];
            System.arraycopy(salt, 0, payload, 0, salt.length);
            System.arraycopy(iv, 0, payload, salt.length, iv.length);
            System.arraycopy(cipherText, 0, payload, salt.length + iv.length, cipherText.length);
            return ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt the JWT private key", e);
        }
    }

    private String decrypt(String stored) throws GeneralSecurityException {
        byte[] payload = Base64.getDecoder().decode(stored.substring(ENCRYPTED_PREFIX.length()));
        if (payload.length <= KDF_SALT_BYTES + GCM_IV_BYTES) {
            throw new GeneralSecurityException("Encrypted JWT private key is truncated");
        }
        byte[] salt = Arrays.copyOfRange(payload, 0, KDF_SALT_BYTES);
        byte[] iv = Arrays.copyOfRange(payload, KDF_SALT_BYTES, KDF_SALT_BYTES + GCM_IV_BYTES);
        byte[] cipherText = Arrays.copyOfRange(payload, KDF_SALT_BYTES + GCM_IV_BYTES, payload.length);

        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(salt), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
    }

    private SecretKey deriveKey(byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(redisKeyEncryptionSecret.toCharArray(), salt, PBKDF2_ITERATIONS, AES_KEY_BITS);
        try {
            byte[] keyBytes = SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private KeyPair loadKeyPairFromPem(String privatePem, String publicPem) throws Exception {
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");

        String cleanPriv = privatePem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] privBytes = Base64.getDecoder().decode(cleanPriv);
        RSAPrivateKey privKey = (RSAPrivateKey) keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privBytes));

        return new KeyPair(parsePublicKey(publicPem), privKey);
    }

    private RSAPublicKey parsePublicKey(String publicPem) throws Exception {
        String cleanPub = publicPem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] pubBytes = Base64.getDecoder().decode(cleanPub);
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(pubBytes));
    }
}

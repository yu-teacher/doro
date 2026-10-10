package com.hunnit_beasts.auth.core.totp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * DB 에 저장하는 2FA(TOTP) 시크릿을 AES-256-GCM 으로 암호화한다. DB 덤프나 백업이 새도 시크릿(=2차 인증 수단)이 그대로 드러나지 않게 하려는 것이다.
 *
 * <p>저장 형식: {@code enc:v1:<base64(iv 12바이트 + 암호문 + 태그)>}. 접두사가 없으면 예전에 평문으로 저장된 값으로 보고 그대로 읽는다
 * (기동 시 {@link TotpSecretMigrator} 가 암호문으로 바꾼다). 키가 설정되지 않으면 이전처럼 평문으로 저장하고 기동 때 경고한다.
 * 암호문이 있는데 키가 없거나 맞지 않으면 평문으로 오인하지 않고 예외로 멈춘다(그 값으로 OTP 를 검증하면 모든 2FA 가 조용히 실패한다).
 *
 * <p>키는 32바이트(256비트) 임의 값의 base64({@code openssl rand -base64 32}). 교체할 때는 새 키를 {@code encryption-key} 로,
 * 이전 키를 {@code previous-keys}(쉼표 구분)에 넣으면 이전 키로 암호화된 값도 읽고, 쓸 때마다 새 키로 바뀐다.
 * AAD 는 컬럼 용도(활성/대기)라 한 컬럼의 암호문을 다른 컬럼으로 옮겨 붙이면 복호화가 실패한다.
 */
@Slf4j
@Component
public class TotpSecretCipher {

    public static final String PREFIX = "enc:v1:";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKeySpec currentKey;
    private final List<SecretKeySpec> decryptKeys;
    private final SecureRandom random = new SecureRandom();

    public TotpSecretCipher(
            @Value("${doro.iam.totp.encryption-key:}") String encryptionKey,
            @Value("${doro.iam.totp.previous-keys:}") String previousKeys) {
        this.currentKey = encryptionKey == null || encryptionKey.isBlank() ? null : parseKey(encryptionKey.trim(), "encryption-key");
        List<SecretKeySpec> keys = new ArrayList<>();
        if (currentKey != null) {
            keys.add(currentKey);
        }
        if (previousKeys != null && !previousKeys.isBlank()) {
            for (String previous : previousKeys.split(",")) {
                if (!previous.isBlank()) {
                    keys.add(parseKey(previous.trim(), "previous-keys"));
                }
            }
        }
        this.decryptKeys = List.copyOf(keys);
        if (currentKey == null) {
            log.warn("doro.iam.totp.encryption-key is not set: 2FA (TOTP) secrets are stored in PLAINTEXT in the database. "
                    + "Set DORO_IAM_TOTP_ENCRYPTION_KEY (openssl rand -base64 32).");
        }
    }

    public boolean isEnabled() {
        return currentKey != null;
    }

    public boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    /** 저장용 값. 키가 없으면 평문 그대로, 이미 암호문이면 그대로 돌려준다. */
    public String encrypt(String plaintext, String purpose) {
        if (plaintext == null || plaintext.isBlank() || currentKey == null || isEncrypted(plaintext)) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, currentKey, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt a TOTP secret", e);
        }
    }

    /** 읽은 값. 접두사가 없으면 예전 평문이므로 그대로 돌려준다. */
    public String decrypt(String stored, String purpose) {
        if (stored == null || stored.isBlank() || !isEncrypted(stored)) {
            return stored;
        }
        if (decryptKeys.isEmpty()) {
            throw new IllegalStateException("An encrypted TOTP secret was found but doro.iam.totp.encryption-key is not configured");
        }
        byte[] raw = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
        if (raw.length <= IV_BYTES) {
            throw new IllegalStateException("Malformed encrypted TOTP secret");
        }
        for (SecretKeySpec key : decryptKeys) {
            try {
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES));
                cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
                return new String(cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES), StandardCharsets.UTF_8);
            } catch (GeneralSecurityException wrongKeyOrTampered) {
                // 다음 키로 시도한다
            }
        }
        throw new IllegalStateException("Could not decrypt a TOTP secret: the encryption key is wrong or the value was modified");
    }

    private static SecretKeySpec parseKey(String base64, String property) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("doro.iam.totp." + property + " is not valid base64");
        }
        if (bytes.length != KEY_BYTES) {
            throw new IllegalStateException("doro.iam.totp." + property + " must be 32 bytes (openssl rand -base64 32)");
        }
        return new SecretKeySpec(bytes, "AES");
    }
}

package com.hunnit_beasts.auth.core.totp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class TotpService {

    private static final String HMAC_ALGO = "HmacSHA1";
    private static final int TIME_STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final int MODULO = 1_000_000;
    private static final String BASE32_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private static final int ALLOWED_STEP_DRIFT = 1;

    private final SecureRandom secureRandom = new SecureRandom();
    // 사용자별로 마지막에 사용한 TOTP 스텝. 같은 코드의 재사용(replay)을 막는다. (단일 인스턴스 메모리 기준)
    private final Map<UUID, Long> lastUsedStepByUser = new ConcurrentHashMap<>();

    /**
     * Google Authenticator 호환 20바이트(160비트) Base32 시크릿 생성
     */
    public String generateSecret() {
        byte[] buffer = new byte[20];
        secureRandom.nextBytes(buffer);
        return encodeBase32(buffer);
    }

    /**
     * QR 코드 스캔용 otpauth URI 생성
     */
    public String generateQrUri(String email, String secret, String issuer) {
        String encodedIssuer = URLEncoder.encode(issuer, StandardCharsets.UTF_8);
        String encodedAccount = URLEncoder.encode(email, StandardCharsets.UTF_8);
        return String.format("otpauth://totp/%s:%s?secret=%s&issuer=%s&algorithm=SHA1&digits=%d&period=%d",
                encodedIssuer, encodedAccount, secret, encodedIssuer, DIGITS, TIME_STEP_SECONDS);
    }

    /**
     * 클라이언트가 입력한 6자리 코드 검증 (시간 오차 ±1 스텝 허용). 코드 재사용 여부는 검사하지 않는다.
     */
    public boolean verifyCode(String base32Secret, String inputCode) {
        return findMatchingStep(base32Secret, inputCode) != null;
    }

    /**
     * 코드 검증 후 사용 처리한다. 이미 사용된 스텝(또는 그 이전 스텝)의 코드는 유효해도 거부한다.
     */
    public boolean verifyAndConsume(UUID userId, String base32Secret, String inputCode) {
        Long step = findMatchingStep(base32Secret, inputCode);
        if (step == null) {
            return false;
        }
        AtomicBoolean accepted = new AtomicBoolean(false);
        lastUsedStepByUser.compute(userId, (id, previous) -> {
            if (previous == null || step > previous) {
                accepted.set(true);
                return step;
            }
            return previous;
        });
        if (!accepted.get()) {
            log.warn("Rejected replayed TOTP code: userId={}", userId);
        }
        return accepted.get();
    }

    private Long findMatchingStep(String base32Secret, String inputCode) {
        if (base32Secret == null || inputCode == null || inputCode.length() != DIGITS) {
            return null;
        }

        try {
            int code = Integer.parseInt(inputCode);
            byte[] key = decodeBase32(base32Secret);
            long currentStep = Instant.now().getEpochSecond() / TIME_STEP_SECONDS;

            for (long step = currentStep - ALLOWED_STEP_DRIFT; step <= currentStep + ALLOWED_STEP_DRIFT; step++) {
                if (generateCodeForStep(key, step) == code) {
                    return step;
                }
            }
        } catch (Exception e) {
            log.warn("TOTP verification failed due to format error: {}", e.getMessage());
        }

        return null;
    }

    /**
     * 드리프트 창을 벗어난 스텝의 사용 기록을 정리한다. (이 스텝들은 더 이상 어떤 코드로도 재사용될 수 없다)
     * 사용자 수에 비례해 메모리가 계속 늘지 않게 하며, 유효 사용자에게는 영향이 없다.
     */
    @Scheduled(fixedDelayString = "${doro.iam.totp.cleanup-interval-ms:300000}",
            initialDelayString = "${doro.iam.totp.cleanup-initial-delay-ms:60000}")
    public void purgeStaleUsedSteps() {
        purgeStaleUsedSteps(Instant.now());
    }

    /** @return 제거한 항목 수 */
    public int purgeStaleUsedSteps(Instant now) {
        long oldestAcceptableStep = now.getEpochSecond() / TIME_STEP_SECONDS - ALLOWED_STEP_DRIFT;
        int before = lastUsedStepByUser.size();
        lastUsedStepByUser.values().removeIf(step -> step < oldestAcceptableStep);
        int removed = before - lastUsedStepByUser.size();
        if (removed > 0) {
            log.debug("Purged {} stale TOTP replay-guard entries", removed);
        }
        return removed;
    }

    public int trackedUserCount() {
        return lastUsedStepByUser.size();
    }

    private int generateCodeForStep(byte[] key, long step) throws NoSuchAlgorithmException, InvalidKeyException {
        byte[] data = ByteBuffer.allocate(8).putLong(step).array();
        Mac mac = Mac.getInstance(HMAC_ALGO);
        mac.init(new SecretKeySpec(key, HMAC_ALGO));
        byte[] hash = mac.doFinal(data);

        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);

        return binary % MODULO;
    }

    private String encodeBase32(byte[] data) {
        StringBuilder result = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;

        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                int index = (buffer >> (bitsLeft - 5)) & 0x1F;
                bitsLeft -= 5;
                result.append(BASE32_CHARS.charAt(index));
            }
        }

        if (bitsLeft > 0) {
            int index = (buffer << (5 - bitsLeft)) & 0x1F;
            result.append(BASE32_CHARS.charAt(index));
        }

        return result.toString();
    }

    private byte[] decodeBase32(String base32) {
        String cleaned = base32.toUpperCase().replaceAll("[^A-Z2-7]", "");
        byte[] result = new byte[cleaned.length() * 5 / 8];
        int buffer = 0;
        int bitsLeft = 0;
        int count = 0;

        for (char c : cleaned.toCharArray()) {
            int val = BASE32_CHARS.indexOf(c);
            if (val < 0) continue;
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                result[count++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }

        return Arrays.copyOf(result, count);
    }
}

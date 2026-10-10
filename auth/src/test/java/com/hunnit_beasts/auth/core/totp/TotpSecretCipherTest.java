package com.hunnit_beasts.auth.core.totp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TotpSecretCipherTest {

    private static final String SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";
    private static final String ACTIVE = TotpSecretConverters.ACTIVE_PURPOSE;
    private static final String PENDING = TotpSecretConverters.PENDING_PURPOSE;

    private static String newKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    @DisplayName("암호화하면 평문이 보이지 않고, 같은 값도 매번 다른 암호문이며, 복호화하면 원래 값이다")
    void roundTrip() {
        TotpSecretCipher cipher = new TotpSecretCipher(newKey(), "");

        String first = cipher.encrypt(SECRET, ACTIVE);
        String second = cipher.encrypt(SECRET, ACTIVE);

        assertThat(first).startsWith(TotpSecretCipher.PREFIX).doesNotContain(SECRET);
        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first, ACTIVE)).isEqualTo(SECRET);
        assertThat(cipher.decrypt(second, ACTIVE)).isEqualTo(SECRET);
        assertThat(first.length()).isLessThanOrEqualTo(255);
    }

    @Test
    @DisplayName("예전 평문 값은 그대로 읽고, 이미 암호문인 값은 다시 암호화하지 않으며, null·빈 값은 그대로 둔다")
    void legacyPlaintextAndIdempotence() {
        TotpSecretCipher cipher = new TotpSecretCipher(newKey(), "");
        String encrypted = cipher.encrypt(SECRET, ACTIVE);

        assertThat(cipher.decrypt(SECRET, ACTIVE)).isEqualTo(SECRET);
        assertThat(cipher.encrypt(encrypted, ACTIVE)).isEqualTo(encrypted);
        assertThat(cipher.encrypt(null, ACTIVE)).isNull();
        assertThat(cipher.encrypt("", ACTIVE)).isEmpty();
        assertThat(cipher.decrypt(null, ACTIVE)).isNull();
    }

    @Test
    @DisplayName("키가 없으면 이전처럼 평문으로 저장·조회하고, 암호문을 만나면 평문으로 오인하지 않고 멈춘다")
    void withoutKey() {
        TotpSecretCipher none = new TotpSecretCipher("", "");
        String encrypted = new TotpSecretCipher(newKey(), "").encrypt(SECRET, ACTIVE);

        assertThat(none.isEnabled()).isFalse();
        assertThat(none.encrypt(SECRET, ACTIVE)).isEqualTo(SECRET);
        assertThat(none.decrypt(SECRET, ACTIVE)).isEqualTo(SECRET);
        assertThatThrownBy(() -> none.decrypt(encrypted, ACTIVE)).isInstanceOf(IllegalStateException.class).hasMessageContaining("not configured");
    }

    @Test
    @DisplayName("다른 키로는 복호화되지 않는다")
    void wrongKey() {
        String encrypted = new TotpSecretCipher(newKey(), "").encrypt(SECRET, ACTIVE);

        assertThatThrownBy(() -> new TotpSecretCipher(newKey(), "").decrypt(encrypted, ACTIVE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("wrong");
    }

    @Test
    @DisplayName("값이 변조되면 복호화가 실패한다(GCM 인증)")
    void tamperedValueIsRejected() {
        TotpSecretCipher cipher = new TotpSecretCipher(newKey(), "");
        byte[] raw = Base64.getDecoder().decode(cipher.encrypt(SECRET, ACTIVE).substring(TotpSecretCipher.PREFIX.length()));
        raw[raw.length - 1] ^= 0x01;
        String tampered = TotpSecretCipher.PREFIX + Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> cipher.decrypt(tampered, ACTIVE)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("한 컬럼(활성)의 암호문을 다른 컬럼(대기)으로 옮겨 붙이면 복호화되지 않는다")
    void purposeIsBound() {
        TotpSecretCipher cipher = new TotpSecretCipher(newKey(), "");
        String active = cipher.encrypt(SECRET, ACTIVE);

        assertThatThrownBy(() -> cipher.decrypt(active, PENDING)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("키 교체: 이전 키로 암호화된 값도 읽고, 새로 쓰면 새 키로 암호화된다")
    void keyRotation() {
        String oldKey = newKey();
        String newKey = newKey();
        String underOldKey = new TotpSecretCipher(oldKey, "").encrypt(SECRET, ACTIVE);

        TotpSecretCipher rotated = new TotpSecretCipher(newKey, oldKey);

        assertThat(rotated.decrypt(underOldKey, ACTIVE)).isEqualTo(SECRET);
        String reencrypted = rotated.encrypt(rotated.decrypt(underOldKey, ACTIVE), ACTIVE);
        assertThat(new TotpSecretCipher(newKey, "").decrypt(reencrypted, ACTIVE)).as("이전 키 없이 새 키만으로 읽힌다").isEqualTo(SECRET);
    }

    @Test
    @DisplayName("키 형식이 틀리면(base64 아님, 32바이트 아님) 기동 시점에 실패한다")
    void badKeyFailsFast() {
        assertThatThrownBy(() -> new TotpSecretCipher("not base64 !!", "")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TotpSecretCipher(Base64.getEncoder().encodeToString(new byte[16]), ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new TotpSecretCipher(newKey(), "short")).isInstanceOf(IllegalStateException.class);
    }
}

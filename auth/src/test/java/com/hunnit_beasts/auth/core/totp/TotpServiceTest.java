package com.hunnit_beasts.auth.core.totp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TotpServiceTest {

    private final TotpService totpService = new TotpService();

    @Test
    @DisplayName("Google Authenticator용 Base32 시크릿 및 QR URI 정상 생성")
    void testGenerateSecretAndQrUri() {
        String secret = totpService.generateSecret();
        assertThat(secret).isNotBlank();
        assertThat(secret.length()).isEqualTo(32);

        String qrUri = totpService.generateQrUri("user@doro.local", secret, "Doro");
        assertThat(qrUri).startsWith("otpauth://totp/Doro:user%40doro.local");
        assertThat(qrUri).contains("secret=" + secret);
        assertThat(qrUri).contains("period=30");
    }

    @Test
    @DisplayName("잘못된 6자리 코드 입력 시 TOTP 검증 실패")
    void testInvalidCodeFails() {
        String secret = totpService.generateSecret();
        boolean isValid = totpService.verifyCode(secret, "000000");
        assertThat(isValid).isFalse();
    }
}

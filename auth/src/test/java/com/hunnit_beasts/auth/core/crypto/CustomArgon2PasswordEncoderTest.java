package com.hunnit_beasts.auth.core.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CustomArgon2PasswordEncoderTest {

    private final CustomArgon2PasswordEncoder encoder = new CustomArgon2PasswordEncoder();

    @Test
    @DisplayName("Argon2id 비밀번호 암호화 및 일치 검증 성공")
    void testEncodeAndMatch() {
        String rawPassword = "P@ssword123!";
        String encoded = encoder.encode(rawPassword);

        assertThat(encoded).startsWith("$argon2id$");
        assertThat(encoder.matches(rawPassword, encoded)).isTrue();
        assertThat(encoder.matches("WrongPassword!", encoded)).isFalse();
    }

    @Test
    @DisplayName("동일한 비밀번호라도 매번 다른 솔트(Salt)가 적용되어 서로 다른 해시 생성")
    void testDifferentSalts() {
        String rawPassword = "SecurePassword123";
        String encoded1 = encoder.encode(rawPassword);
        String encoded2 = encoder.encode(rawPassword);

        assertThat(encoded1).isNotEqualTo(encoded2);
        assertThat(encoder.matches(rawPassword, encoded1)).isTrue();
        assertThat(encoder.matches(rawPassword, encoded2)).isTrue();
    }
}

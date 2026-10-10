package com.hunnit_beasts.auth;

import com.hunnit_beasts.auth.core.totp.TotpSecretCipher;
import com.hunnit_beasts.auth.core.totp.TotpSecretMigrator;
import com.hunnit_beasts.auth.core.totp.TotpService;
import com.hunnit_beasts.auth.domain.auth.dto.LoginRequest;
import com.hunnit_beasts.auth.domain.auth.dto.SignUpRequest;
import com.hunnit_beasts.auth.domain.auth.service.AuthService;
import com.hunnit_beasts.auth.domain.credential.repository.CredentialRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 2FA 시크릿 암호화를 켠 상태(키 설정)에서 실제 DB 저장값, 2FA 등록·로그인, 예전 평문 값의 이전. */
@SpringBootTest(properties = "doro.iam.totp.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
@ActiveProfiles("test")
class TotpSecretEncryptionTest {

    private static final String PASSWORD = "Password123!";
    private static final String LEGACY_SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

    @Autowired private AuthService authService;
    @Autowired private CredentialRepository credentialRepository;
    @Autowired private TotpService totpService;
    @Autowired private TotpSecretMigrator migrator;
    @Autowired private TotpSecretCipher cipher;
    @Autowired private JdbcTemplate jdbc;

    private String email;

    private UUID newUser() {
        email = "totp-enc-" + UUID.randomUUID() + "@doro.local";
        return authService.signup(new SignUpRequest(email, PASSWORD, "Totp Enc"));
    }

    private String rawActive(UUID userId) {
        return jdbc.queryForObject("select totp_secret from credentials where user_id = ?", String.class, userId);
    }

    private String rawPending(UUID userId) {
        return jdbc.queryForObject("select pending_totp_secret from credentials where user_id = ?", String.class, userId);
    }

    private String code(String secret) {
        byte[] key = ReflectionTestUtils.invokeMethod(totpService, "decodeBase32", secret);
        int value = ReflectionTestUtils.invokeMethod(totpService, "generateCodeForStep", key, Instant.now().getEpochSecond() / 30);
        return String.format("%06d", value);
    }

    @Test
    @DisplayName("2FA 를 등록하면 DB 에는 암호문이 저장되고(대기·활성 모두), 코드 확인과 2FA 로그인은 평소처럼 동작한다")
    void enrollmentStoresCiphertextAndStillWorks() {
        UUID userId = newUser();

        authService.setupTotp(userId, PASSWORD);
        String secret = credentialRepository.findByUserId(userId).orElseThrow().getPendingTotpSecret();
        assertThat(secret).as("엔티티에서는 평문").doesNotStartWith(TotpSecretCipher.PREFIX);
        assertThat(rawPending(userId)).startsWith(TotpSecretCipher.PREFIX).doesNotContain(secret);

        authService.verifyTotp(userId, code(secret));
        assertThat(rawActive(userId)).startsWith(TotpSecretCipher.PREFIX).doesNotContain(secret);
        assertThat(rawPending(userId)).isNull();

        var login = authService.login(new LoginRequest(email, PASSWORD, "test"), "127.0.0.1", "UA-" + UUID.randomUUID());
        assertThat(login.requires2fa()).isTrue();
    }

    @Test
    @DisplayName("예전에 평문으로 저장된 시크릿도 그대로 읽히고, 이전(migrate)하면 암호문이 되며 이후에도 같은 값으로 읽힌다")
    void legacyPlaintextIsReadableAndMigrated() {
        UUID userId = newUser();
        jdbc.update("update credentials set totp_secret = ?, pending_totp_secret = ? where user_id = ?", LEGACY_SECRET, LEGACY_SECRET, userId);

        var before = credentialRepository.findByUserId(userId).orElseThrow();
        assertThat(before.getTotpSecret()).isEqualTo(LEGACY_SECRET);
        assertThat(before.getPendingTotpSecret()).isEqualTo(LEGACY_SECRET);

        assertThat(migrator.migrate()).isGreaterThanOrEqualTo(1);

        assertThat(rawActive(userId)).startsWith(TotpSecretCipher.PREFIX).doesNotContain(LEGACY_SECRET);
        assertThat(rawPending(userId)).startsWith(TotpSecretCipher.PREFIX).doesNotContain(LEGACY_SECRET);
        var after = credentialRepository.findByUserId(userId).orElseThrow();
        assertThat(after.getTotpSecret()).isEqualTo(LEGACY_SECRET);
        assertThat(after.getPendingTotpSecret()).isEqualTo(LEGACY_SECRET);
        assertThat(migrator.migrate()).as("두 번째 실행은 바꿀 것이 없다(멱등)").isZero();
    }

    @Test
    @DisplayName("DB 에서 대기 시크릿의 암호문을 활성 컬럼으로 옮겨 붙여도(2FA 우회 시도) 복호화되지 않는다")
    void movingCiphertextBetweenColumnsDoesNotWork() {
        UUID userId = newUser();
        authService.setupTotp(userId, PASSWORD);
        String pendingCipher = rawPending(userId);

        jdbc.update("update credentials set totp_secret = ? where user_id = ?", pendingCipher, userId);

        assertThatThrownBy(() -> credentialRepository.findByUserId(userId))
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(cipher.isEnabled()).isTrue();
    }
}

package com.hunnit_beasts.auth;

import com.hunnit_beasts.auth.domain.credential.entity.Credential;
import com.hunnit_beasts.auth.domain.credential.repository.CredentialRepository;
import com.hunnit_beasts.auth.domain.credential.service.CredentialService;
import com.hunnit_beasts.auth.domain.user.entity.User;
import com.hunnit_beasts.auth.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 잠금이 풀린 직후의 첫 실패가 곧바로 다시 잠그지 않는지(계속 잠가 두는 DoS 방지) 검증한다. */
@SpringBootTest
@ActiveProfiles("test")
class AccountLockExpiryTest {

    @Autowired private UserRepository userRepository;
    @Autowired private CredentialRepository credentialRepository;
    @Autowired private CredentialService credentialService;
    @Autowired private JdbcTemplate jdbc;

    private UUID newCredential() {
        UUID userId = userRepository.saveAndFlush(User.builder()
                .email("lock-" + UUID.randomUUID() + "@doro.test").name("lock").build()).getId();
        credentialRepository.saveAndFlush(Credential.builder().userId(userId).passwordHash("x").build());
        return userId;
    }

    private Credential reload(UUID userId) {
        return credentialRepository.findByUserId(userId).orElseThrow();
    }

    @Test
    @DisplayName("연속 5번 실패하면 잠긴다 (기본 동작 유지)")
    void locksAfterFiveFailures() {
        UUID userId = newCredential();
        for (int i = 0; i < Credential.MAX_FAILED_ATTEMPTS; i++) {
            credentialService.recordFailedAttempt(userId);
        }

        assertThat(reload(userId).getLockedUntil()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("잠금이 만료된 뒤의 첫 실패는 카운터를 1부터 다시 세고 즉시 재잠금하지 않는다")
    void firstFailureAfterExpiredLockDoesNotRelock() {
        UUID userId = newCredential();
        for (int i = 0; i < Credential.MAX_FAILED_ATTEMPTS; i++) {
            credentialService.recordFailedAttempt(userId);
        }
        // 잠금 시간이 지난 상태를 만든다 (카운터는 DB 에 5 로 남아 있다)
        jdbc.update("update credentials set locked_until = ? where user_id = ?",
                java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.MINUTES)), userId);

        credentialService.recordFailedAttempt(userId);

        Credential after = reload(userId);
        assertThat(after.getFailedAttempts()).as("만료 후 카운터는 1부터").isEqualTo(1);
        assertThat(after.getLockedUntil()).as("즉시 재잠금되지 않는다").isNull();
    }

    @Test
    @DisplayName("만료 후에도 5번 연속 실패하면 다시 잠긴다")
    void locksAgainAfterFiveMoreFailures() {
        UUID userId = newCredential();
        for (int i = 0; i < Credential.MAX_FAILED_ATTEMPTS; i++) {
            credentialService.recordFailedAttempt(userId);
        }
        jdbc.update("update credentials set locked_until = ? where user_id = ?",
                java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.MINUTES)), userId);

        for (int i = 0; i < Credential.MAX_FAILED_ATTEMPTS; i++) {
            credentialService.recordFailedAttempt(userId);
        }

        assertThat(reload(userId).getLockedUntil()).isAfter(Instant.now());
    }
}

package com.hunnit_beasts.auth;

import com.hunnit_beasts.auth.domain.credential.entity.Credential;
import com.hunnit_beasts.auth.domain.credential.repository.CredentialRepository;
import com.hunnit_beasts.auth.domain.credential.service.CredentialService;
import com.hunnit_beasts.auth.domain.user.entity.User;
import com.hunnit_beasts.auth.domain.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계정 전체 잠금(DB 기록)의 시작과 만료. 여러 IP 가 합쳐 30번 틀리면 잠기고, 잠금이 풀린 직후의 첫 실패 한 번이
 * 곧바로 다시 잠그지 않는다(계속 잠가 두는 DoS 방지). IP 마다 4번(쌍 잠금 아래)씩 나눠 틀린다.
 */
@SpringBootTest
@ActiveProfiles("test")
class AccountLockExpiryTest {

    @Autowired private UserRepository userRepository;
    @Autowired private CredentialRepository credentialRepository;
    @Autowired private CredentialService credentialService;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private UUID newCredential() {
        UUID userId = userRepository.saveAndFlush(User.builder()
                .email("lock-" + UUID.randomUUID() + "@doro.test").name("lock").build()).getId();
        credentialRepository.saveAndFlush(Credential.builder().userId(userId).passwordHash("x").build());
        return userId;
    }

    private Credential reload(UUID userId) {
        return credentialRepository.findByUserId(userId).orElseThrow();
    }

    /** 지정한 IP 에서 온 요청인 것처럼 실패를 기록한다(루프백 프록시가 X-Real-IP 를 붙인 상황). */
    private void failFrom(String ip, UUID userId, int times) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Real-IP", ip);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        for (int i = 0; i < times; i++) {
            credentialService.recordFailedAttempt(userId);
        }
    }

    /** 서로 다른 IP 에서 4번씩 → 쌍 잠금(5번)에는 닿지 않고 합계만 늘어난다. */
    private void failAcrossIps(UUID userId, int totalFailures, String ipPrefix) {
        int ip = 0;
        for (int left = totalFailures; left > 0; left -= 4) {
            failFrom(ipPrefix + (++ip), userId, Math.min(4, left));
        }
    }

    @Test
    @DisplayName("한 IP 에서 아무리 틀려도 DB 의 계정 잠금은 걸리지 않는다 (쌍 잠금만 걸린다)")
    void oneIpNeverLocksTheAccountRecord() {
        UUID userId = newCredential();
        failFrom("203.0.113.1", userId, 100);

        assertThat(reload(userId).getLockedUntil()).isNull();
    }

    @Test
    @DisplayName("여러 IP 가 합쳐 30번 틀리면 계정이 잠기고 약 1시간 뒤까지 유지된다")
    void locksAfterThirtyDistributedFailures() {
        UUID userId = newCredential();
        failAcrossIps(userId, 30, "198.51.100.");

        Instant lockedUntil = reload(userId).getLockedUntil();
        assertThat(lockedUntil).isAfter(Instant.now().plus(55, ChronoUnit.MINUTES)).isBefore(Instant.now().plus(65, ChronoUnit.MINUTES));
    }

    @Test
    @DisplayName("29번까지는 잠기지 않는다")
    void doesNotLockBeforeTheThreshold() {
        UUID userId = newCredential();
        failAcrossIps(userId, 29, "198.51.100.");

        assertThat(reload(userId).getLockedUntil()).isNull();
    }

    @Test
    @DisplayName("잠금이 만료된 뒤의 첫 실패는 다시 잠그지 않는다")
    void firstFailureAfterExpiredLockDoesNotRelock() {
        UUID userId = newCredential();
        failAcrossIps(userId, 30, "198.51.100.");
        // 잠금 시간이 지난 상태를 만든다
        jdbc.update("update credentials set locked_until = ? where user_id = ?",
                java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.MINUTES)), userId);

        failFrom("192.0.2.77", userId, 1);

        Instant lockedUntil = reload(userId).getLockedUntil();
        assertThat(lockedUntil == null || lockedUntil.isBefore(Instant.now())).as("즉시 재잠금되지 않는다").isTrue();
    }

    @Test
    @DisplayName("만료 후 다시 30번 틀리면 다시 잠긴다")
    void locksAgainAfterThirtyMoreFailures() {
        UUID userId = newCredential();
        failAcrossIps(userId, 30, "198.51.100.");
        jdbc.update("update credentials set locked_until = ? where user_id = ?",
                java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.MINUTES)), userId);

        failAcrossIps(userId, 30, "192.0.2.");

        assertThat(reload(userId).getLockedUntil()).isAfter(Instant.now());
    }
}

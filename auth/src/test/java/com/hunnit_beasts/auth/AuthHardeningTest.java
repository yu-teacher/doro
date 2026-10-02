package com.hunnit_beasts.auth;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.core.token.RefreshTokenService;
import com.hunnit_beasts.auth.core.totp.TotpService;
import com.hunnit_beasts.auth.domain.credential.entity.Credential;
import com.hunnit_beasts.auth.domain.credential.repository.CredentialRepository;
import com.hunnit_beasts.auth.domain.credential.service.CredentialService;
import com.hunnit_beasts.auth.domain.session.entity.UserSession;
import com.hunnit_beasts.auth.domain.user.entity.User;
import com.hunnit_beasts.auth.domain.user.repository.UserRepository;
import com.hunnit_beasts.auth.domain.session.repository.UserSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AuthHardeningTest {

    @Autowired
    private RefreshTokenService refreshTokenService;
    @Autowired
    private UserSessionRepository userSessionRepository;
    @Autowired
    private CredentialService credentialService;
    @Autowired
    private CredentialRepository credentialRepository;
    @Autowired
    private TotpService totpService;
    @Autowired
    private UserRepository userRepository;

    /** 세션·자격증명은 users 를 FK 로 참조한다 (실제 PostgreSQL 스키마에서는 FK 가 강제된다). */
    private UUID persistedUserId() {
        return userRepository.saveAndFlush(User.builder()
                .email("hardening-" + UUID.randomUUID() + "@doro.test")
                .name("hardening")
                .build()).getId();
    }

    private UserSession newSession() {
        return userSessionRepository.save(UserSession.builder()
                .userId(persistedUserId())
                .userIndex(0)
                .expiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
                .build());
    }

    @Test
    @DisplayName("N3: 같은 리프레시 토큰으로 동시에 회전을 요청해도 정확히 하나만 성공한다")
    void concurrentRotationHasSingleWinner() throws Exception {
        UserSession session = newSession();
        String token = refreshTokenService.createRefreshToken(session.getId());

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Void> task = () -> {
                start.await();
                try {
                    refreshTokenService.rotateRefreshToken(token);
                    successes.incrementAndGet();
                } catch (AuthException expected) {
                    // 경합에서 진 요청
                }
                return null;
            };
            futures.add(pool.submit(task));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(successes.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("N3: 유예 시간(grace)을 켜면 회전 직후의 직전 토큰은 공격으로 보지 않고 세션을 유지한다")
    void graceWindowKeepsSessionAlive() {
        UserSession session = newSession();
        String first = refreshTokenService.createRefreshToken(session.getId());
        RefreshTokenService.RotatedTokenResult rotated = refreshTokenService.rotateRefreshToken(first);

        ReflectionTestUtils.setField(refreshTokenService, "refreshReuseGraceSeconds", 10L);
        try {
            assertThatThrownBy(() -> refreshTokenService.rotateRefreshToken(first))
                    .isInstanceOfSatisfying(AuthException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_TOKEN));
            // 정상 토큰은 여전히 회전할 수 있다
            assertThat(refreshTokenService.rotateRefreshToken(rotated.newRefreshToken()).newRefreshToken()).isNotBlank();
        } finally {
            ReflectionTestUtils.setField(refreshTokenService, "refreshReuseGraceSeconds", 0L);
        }
    }

    @Test
    @DisplayName("N3: 유예 시간이 꺼져 있으면(기본) 회전된 토큰 재사용은 즉시 공격으로 판정한다")
    void reuseIsAttackByDefault() {
        UserSession session = newSession();
        String first = refreshTokenService.createRefreshToken(session.getId());
        refreshTokenService.rotateRefreshToken(first);

        assertThatThrownBy(() -> refreshTokenService.rotateRefreshToken(first))
                .isInstanceOfSatisfying(AuthException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TOKEN_REUSE_DETECTED));
    }

    @Test
    @DisplayName("N5: 동시 로그인 실패도 카운트가 유실되지 않고 임계치에서 잠긴다")
    void concurrentFailedAttemptsAreAllCounted() throws Exception {
        UUID userId = persistedUserId();
        credentialRepository.saveAndFlush(Credential.builder().userId(userId).passwordHash("x").build());

        int attempts = 20;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            futures.add(pool.submit(() -> credentialService.recordFailedAttempt(userId)));
        }
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        Credential credential = credentialRepository.findByUserId(userId).orElseThrow();
        assertThat(credential.getFailedAttempts()).isEqualTo(attempts);
        assertThat(credential.isLocked()).isTrue();
    }

    private String currentCode(String secret) {
        byte[] key = ReflectionTestUtils.invokeMethod(totpService, "decodeBase32", secret);
        long step = Instant.now().getEpochSecond() / 30;
        int code = ReflectionTestUtils.invokeMethod(totpService, "generateCodeForStep", key, step);
        return String.format("%06d", code);
    }

    @Test
    @DisplayName("N6: 한 번 사용한 TOTP 코드는 유효 시간 안에 다시 쓸 수 없다")
    void totpCodeCannotBeReplayed() {
        UUID userId = UUID.randomUUID();
        String secret = totpService.generateSecret();
        String code = currentCode(secret);

        assertThat(totpService.verifyAndConsume(userId, secret, code)).isTrue();
        assertThat(totpService.verifyAndConsume(userId, secret, code)).isFalse();
        // 코드 검증 자체(재사용 검사 없음)는 여전히 유효 코드를 인정한다
        assertThat(totpService.verifyCode(secret, code)).isTrue();
    }

    @Test
    @DisplayName("N6: 시간 오차 허용은 ±1 스텝이다 (±2 스텝 코드는 거부)")
    void totpDriftIsLimitedToOneStep() {
        String secret = totpService.generateSecret();
        byte[] key = ReflectionTestUtils.invokeMethod(totpService, "decodeBase32", secret);
        long step = Instant.now().getEpochSecond() / 30;

        int nearCode = ReflectionTestUtils.invokeMethod(totpService, "generateCodeForStep", key, step - 1);
        int farCode = ReflectionTestUtils.invokeMethod(totpService, "generateCodeForStep", key, step - 3);

        assertThat(totpService.verifyCode(secret, String.format("%06d", nearCode))).isTrue();
        assertThat(totpService.verifyCode(secret, String.format("%06d", farCode))).isFalse();
    }
}

package com.hunnit_beasts.auth;

import com.hunnit_beasts.auth.domain.credential.service.LoginAttemptTracker;
import com.hunnit_beasts.auth.domain.credential.service.LoginAttemptTracker.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** 가짜 시계로 보는 잠금 정책의 시간 동작: 쌍 5회/15분, 계정 30회/1시간. */
class LoginAttemptTrackerTest {

    private static final long MIN = 60_000L;

    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private LoginAttemptTracker tracker;
    private final UUID user = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        tracker = new LoginAttemptTracker(clock::get, 5, Duration.ofMinutes(15), Duration.ofMinutes(15),
                30, Duration.ofMinutes(60), Duration.ofMinutes(60));
    }

    private void fails(String ip, int n) {
        for (int i = 0; i < n; i++) {
            tracker.recordFailure(user, ip);
        }
    }

    @Test
    @DisplayName("다섯 번째 실패에서 그 쌍이 잠기고, 정확히 15분 뒤에 풀린다")
    void pairLocksAtFiveAndExpiresAfterFifteenMinutes() {
        fails("1.1.1.1", 4);
        assertThat(tracker.isPairLocked(user, "1.1.1.1")).isFalse();

        assertThat(tracker.recordFailure(user, "1.1.1.1")).isEqualTo(Result.PAIR_LOCKED);
        assertThat(tracker.isPairLocked(user, "1.1.1.1")).isTrue();
        assertThat(tracker.isPairLocked(user, "2.2.2.2")).as("다른 IP").isFalse();

        clock.addAndGet(15 * MIN - 1);
        assertThat(tracker.isPairLocked(user, "1.1.1.1")).isTrue();
        clock.addAndGet(2);
        assertThat(tracker.isPairLocked(user, "1.1.1.1")).isFalse();
    }

    @Test
    @DisplayName("15분이 지난 실패는 잊는다: 천천히 틀리면 잠기지 않는다")
    void slowFailuresDoNotLock() {
        for (int i = 0; i < 20; i++) {
            assertThat(tracker.recordFailure(user, "1.1.1.1")).isEqualTo(Result.COUNTED);
            clock.addAndGet(4 * MIN);   // 15분 창에는 늘 3~4번만 남는다
        }
        assertThat(tracker.isPairLocked(user, "1.1.1.1")).isFalse();
    }

    @Test
    @DisplayName("잠긴 쌍의 시도는 세지 않는다(IGNORED)")
    void lockedPairAttemptsAreIgnored() {
        fails("1.1.1.1", 5);
        assertThat(tracker.recordFailure(user, "1.1.1.1")).isEqualTo(Result.IGNORED);
    }

    @Test
    @DisplayName("IP 하나는 몇 시간을 두드려도 계정 전체 잠금(30회)에 닿지 못한다")
    void aSingleIpNeverReachesTheAccountThreshold() {
        for (int minute = 0; minute < 6 * 60; minute++) {   // 6시간, 매분 시도
            Result r = tracker.recordFailure(user, "1.1.1.1");
            assertThat(r).isNotEqualTo(Result.ACCOUNT_LOCKED);
            clock.addAndGet(MIN);
        }
    }

    @Test
    @DisplayName("여러 IP 가 1시간 안에 합쳐 30번이면 계정 전체 잠금 신호가 나온다")
    void distributedFailuresReachTheAccountThreshold() {
        Result last = Result.COUNTED;
        for (int ip = 1; ip <= 8 && last != Result.ACCOUNT_LOCKED; ip++) {
            for (int i = 0; i < 4 && last != Result.ACCOUNT_LOCKED; i++) {
                last = tracker.recordFailure(user, "10.0.0." + ip);
            }
        }
        assertThat(last).isEqualTo(Result.ACCOUNT_LOCKED);
    }

    @Test
    @DisplayName("1시간이 지난 실패는 계정 합산에서 빠진다")
    void accountWindowSlides() {
        for (int ip = 1; ip <= 7; ip++) {
            fails("10.0.0." + ip, 4);          // 28번
        }
        clock.addAndGet(61 * MIN);
        for (int ip = 1; ip <= 7; ip++) {
            assertThat(tracker.recordFailure(user, "10.1.0." + ip)).isEqualTo(Result.COUNTED);
        }
    }

    @Test
    @DisplayName("성공은 그 IP 의 쌍만 초기화하고 계정 합산은 남긴다")
    void successClearsOnlyThePair() {
        fails("1.1.1.1", 4);
        tracker.recordSuccess(user, "1.1.1.1");
        assertThat(tracker.recordFailure(user, "1.1.1.1")).as("0 부터 다시").isEqualTo(Result.COUNTED);

        // 합산은 남는다: 성공 전 26번 + 이후 4번 = 30
        UUID other = UUID.randomUUID();
        for (int ip = 1; ip <= 6; ip++) {
            for (int i = 0; i < 4; i++) tracker.recordFailure(other, "9.9.9." + ip);   // 24번
        }
        tracker.recordSuccess(other, "9.9.9.1");
        Result r = Result.COUNTED;
        for (int i = 0; i < 6 && r != Result.ACCOUNT_LOCKED; i++) {
            r = tracker.recordFailure(other, "8.8.8." + (i + 1));
        }
        assertThat(r).as("성공이 합산을 지우지 않았다").isEqualTo(Result.ACCOUNT_LOCKED);
    }

    @Test
    @DisplayName("관리자 해제(clearAccount)는 쌍 잠금과 합산을 모두 지운다")
    void clearAccountResetsEverything() {
        fails("1.1.1.1", 5);
        fails("2.2.2.2", 3);
        tracker.clearAccount(user);

        assertThat(tracker.isPairLocked(user, "1.1.1.1")).isFalse();
        assertThat(tracker.recordFailure(user, "2.2.2.2")).isEqualTo(Result.COUNTED);
    }

    @Test
    @DisplayName("다른 계정의 실패는 섞이지 않는다")
    void accountsAreIndependent() {
        fails("1.1.1.1", 5);
        assertThat(tracker.isPairLocked(UUID.randomUUID(), "1.1.1.1")).isFalse();
    }

    @Test
    @DisplayName("만료된 항목은 purge 로 치워진다")
    void purgeRemovesExpiredEntries() {
        for (int i = 0; i < 50; i++) {
            tracker.recordFailure(UUID.randomUUID(), "7.7.7." + i);
        }
        clock.addAndGet(2 * 60 * MIN);
        tracker.purgeExpired();
        // 치운 뒤에도 정상 동작한다
        assertThat(tracker.recordFailure(user, "1.1.1.1")).isEqualTo(Result.COUNTED);
    }
}

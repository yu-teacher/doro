package com.hunnit_beasts.auth.core.crypto;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 해싱 동시성·대기 줄 상한. 폭주해도 서버 스레드를 무한정 붙잡지 않고, 넘치는 요청은 빠르게 거절해야 한다. */
class CustomArgon2PasswordEncoderBackpressureTest {

    private record Outcome(boolean ok, boolean busy, long millis) {
    }

    /** 모든 호출자를 한꺼번에 출발시켜 matches 를 호출하고 결과를 모은다. */
    private List<Outcome> storm(CustomArgon2PasswordEncoder encoder, String hash, int callers) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                long t0 = System.nanoTime();
                try {
                    boolean matched = encoder.matches("Password123!", hash);
                    return new Outcome(matched, false, (System.nanoTime() - t0) / 1_000_000);
                } catch (AuthException e) {
                    return new Outcome(false, e.getErrorCode() == ErrorCode.SERVER_BUSY, (System.nanoTime() - t0) / 1_000_000);
                }
            }));
        }
        go.countDown();
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> f : futures) {
            outcomes.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return outcomes;
    }

    @Test
    @DisplayName("줄이 가득 차면 기다리지 않고 즉시 SERVER_BUSY 로 거절한다")
    void fullQueueRejectsImmediately() throws Exception {
        String hash = new CustomArgon2PasswordEncoder().encode("Password123!");
        // 동시 1개 + 대기 1개 = 2개까지만 받고, 대기 시간은 길게(10초) 둬서 "줄 상한" 때문에 거절됨을 구분한다
        CustomArgon2PasswordEncoder encoder = new CustomArgon2PasswordEncoder(1, 1, 10_000);

        List<Outcome> outcomes = storm(encoder, hash, 8);

        long busy = outcomes.stream().filter(Outcome::busy).count();
        long ok = outcomes.stream().filter(Outcome::ok).count();
        assertThat(ok + busy).isEqualTo(8);
        assertThat(busy).as("넘친 요청은 거절된다").isGreaterThanOrEqualTo(1);
        assertThat(ok).as("받은 요청은 정상 처리된다").isGreaterThanOrEqualTo(1);
        assertThat(outcomes.stream().filter(Outcome::busy).mapToLong(Outcome::millis).max().orElse(0))
                .as("거절은 해싱을 기다리지 않고 바로 나온다(대기 시간 10초와 무관)").isLessThan(1_000);
    }

    @Test
    @DisplayName("줄에서 너무 오래 기다리면 SERVER_BUSY 로 거절한다")
    void waitTimeoutRejects() throws Exception {
        String hash = new CustomArgon2PasswordEncoder().encode("Password123!");
        // 줄은 넉넉하지만 대기 시간이 해싱 1회(수십~수백 ms)보다 짧다
        CustomArgon2PasswordEncoder encoder = new CustomArgon2PasswordEncoder(1, 10, 5);

        List<Outcome> outcomes = storm(encoder, hash, 4);

        assertThat(outcomes.stream().filter(Outcome::busy).count()).isGreaterThanOrEqualTo(1);
        assertThat(outcomes.stream().filter(Outcome::ok).count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("거절이 쏟아진 뒤에도 자리가 새지 않아 다음 요청이 정상 처리된다")
    void noLeakAfterRejections() throws Exception {
        String hash = new CustomArgon2PasswordEncoder().encode("Password123!");
        CustomArgon2PasswordEncoder encoder = new CustomArgon2PasswordEncoder(1, 0, 10_000);

        storm(encoder, hash, 6);

        for (int i = 0; i < 3; i++) {
            assertThat(encoder.matches("Password123!", hash)).isTrue();
        }
        assertThat(encoder.matches("wrong-password", hash)).isFalse();
    }

    @Test
    @DisplayName("한가할 때는 거절 없이 모두 처리한다")
    void idleServesEveryone() throws Exception {
        String hash = new CustomArgon2PasswordEncoder().encode("Password123!");
        CustomArgon2PasswordEncoder encoder = new CustomArgon2PasswordEncoder();   // 기본값: 동시 8, 줄 32

        List<Outcome> outcomes = storm(encoder, hash, 8);

        assertThat(outcomes).allMatch(Outcome::ok);
    }
}

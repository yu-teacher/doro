package com.hunnit_beasts.doro.sdk.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DoroUserContextThreadSafetyTest {

    @Test
    @DisplayName("스레드로컬 동시성 격리: 20개 스레드가 서로 다른 사용자로 동시 작업 시 컨텍스트 오염 및 누수 0% 검증")
    void testConcurrentThreadLocalIsolation() throws InterruptedException {
        int threadCount = 20;
        int iterationsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount * iterationsPerThread);
        AtomicInteger successMatches = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                for (int i = 0; i < iterationsPerThread; i++) {
                    UUID threadSpecificUserId = UUID.randomUUID();
                    String email = "user." + threadSpecificUserId + "@doro.local";
                    DoroUser user = new DoroUser(threadSpecificUserId, email, UUID.randomUUID(), 0);

                    try {
                        DoroUserContext.setCurrentUser(user);

                        // 약간의 연산 지연 시뮬레이션
                        Thread.sleep(2);

                        DoroUser readUser = DoroUserContext.getCurrentUser();
                        if (readUser.userId().equals(threadSpecificUserId) && readUser.email().equals(email)) {
                            successMatches.incrementAndGet();
                        }
                    } catch (InterruptedException ignored) {
                    } finally {
                        DoroUserContext.clear();
                        latch.countDown();
                    }
                }
            });
        }

        boolean completed = latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(successMatches.get()).isEqualTo(threadCount * iterationsPerThread);

        // 모든 스레드 작업 완료 후 메인 스레드는 여전히 anonymous 여야 함
        assertThat(DoroUserContext.getCurrentUser().isAuthenticated()).isFalse();
    }
}

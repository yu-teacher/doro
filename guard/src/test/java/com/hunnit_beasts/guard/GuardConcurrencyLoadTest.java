package com.hunnit_beasts.guard;

import com.hunnit_beasts.guard.core.engine.CheckEngine;
import com.hunnit_beasts.guard.domain.tuple.dto.TupleDto;
import com.hunnit_beasts.guard.domain.tuple.service.TupleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class GuardConcurrencyLoadTest {

    @Autowired
    private CheckEngine checkEngine;

    @Autowired
    private TupleService tupleService;

    @Test
    @DisplayName("동시성 고부하 테스트: 20개 스레드가 수천 건의 복합 그래프 Check를 동시 질의할 때 데드락 및 예외 없이 완벽 처리")
    void testConcurrentCheckEvaluations() throws InterruptedException {
        // 복합 그래프 튜플 데이터 사전 구축
        List<TupleDto> seedTuples = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            seedTuples.add(TupleDto.of("folder", "f-load-" + i, "viewer", "user", "user-" + i));
            seedTuples.add(TupleDto.of("document", "doc-load-" + i, "parent", "folder", "f-load-" + i));
        }
        tupleService.writeTuples(seedTuples);

        int threadCount = 20;
        int requestsPerThread = 50;
        int totalRequests = threadCount * requestsPerThread;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(totalRequests);
        AtomicInteger successCount = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                for (int r = 0; r < requestsPerThread; r++) {
                    try {
                        int targetIdx = (threadId * requestsPerThread + r) % 50;
                        CheckEngine.CheckResult result = checkEngine.check(
                                "document", "doc-load-" + targetIdx, "viewer", "user", "user-" + targetIdx, null
                        );
                        if (result.allowed()) {
                            successCount.incrementAndGet();
                        }
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }

        boolean completed = latch.await(15, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - startTime;
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(totalRequests);
        System.out.printf("Processed %d concurrent ReBAC checks in %d ms (Avg: %.2f µs/check)\n",
                totalRequests, elapsed, (double) (elapsed * 1000) / totalRequests);
    }
}

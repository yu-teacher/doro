package com.hunnit_beasts.auth;

import com.hunnit_beasts.auth.domain.auth.dto.LoginRequest;
import com.hunnit_beasts.auth.domain.auth.dto.LoginResponse;
import com.hunnit_beasts.auth.domain.auth.dto.SignUpRequest;
import com.hunnit_beasts.auth.domain.auth.service.AuthService;
import com.hunnit_beasts.auth.domain.session.dto.SessionResponse;
import com.hunnit_beasts.auth.domain.session.service.SessionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ConcurrencyLoadTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private SessionService sessionService;

    @Test
    @DisplayName("동시성 부하 테스트: 5개 스레드 동시 회원가입 시 데드락 없이 전원 가입 성공")
    void testConcurrentSignups() throws InterruptedException {
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    String email = "concurrent.user." + index + "@doro.local";
                    UUID id = authService.signup(new SignUpRequest(email, "Password123!", "User " + index));
                    if (id != null) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(threadCount);
    }

    @Test
    @DisplayName("동시성 부하 테스트: 동일 계정으로 5개 기기에서 동시 로그인 시 세션 인덱스(u/0, u/1...) 충돌 없이 생성")
    void testConcurrentLoginsSameUser() throws InterruptedException {
        String email = "multidevice.user@doro.local";
        UUID userId = authService.signup(new SignUpRequest(email, "Password123!", "Multi Device"));

        int loginCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(loginCount);
        CountDownLatch latch = new CountDownLatch(loginCount);
        List<LoginResponse> responses = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < loginCount; i++) {
            final int devId = i;
            executor.submit(() -> {
                try {
                    LoginResponse resp = authService.login(
                            new LoginRequest(email, "Password123!", "Device-" + devId),
                            "192.168.1." + devId,
                            "Agent-" + devId
                    );
                    responses.add(resp);
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(responses).hasSize(loginCount);

        // 생성된 세션 목록 검증
        List<SessionResponse> activeSessions = sessionService.getActiveSessions(userId);
        assertThat(activeSessions).hasSize(loginCount);
    }
}

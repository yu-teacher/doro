package com.hunnit_beasts.auth;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.domain.auth.dto.LoginRequest;
import com.hunnit_beasts.auth.domain.auth.dto.SignUpRequest;
import com.hunnit_beasts.auth.domain.auth.service.AuthService;
import com.hunnit_beasts.auth.domain.credential.repository.CredentialRepository;
import com.hunnit_beasts.auth.infrastructure.guard.GuardClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 해싱 자리가 없어 거절(SERVER_BUSY)된 로그인은 "비밀번호가 틀렸다"가 아니다.
 * 실패 횟수에 합산되면, 폭주 때 정상 사용자가 5번 거절당해 계정이 잠기는 사고가 난다.
 */
@SpringBootTest(properties = {
        "doro.iam.password-hash.max-concurrency=1",
        "doro.iam.password-hash.max-queue=0"
})
@ActiveProfiles("test")
class HashingBackpressureTest {

    @Autowired private AuthService authService;
    @Autowired private CredentialRepository credentialRepository;
    @Autowired private com.hunnit_beasts.auth.domain.credential.service.LoginAttemptTracker tracker;
    @MockitoBean private GuardClient guardClient;

    @Test
    @DisplayName("혼잡으로 거절된 로그인은 실패 횟수에 합산되지 않는다")
    void busyRejectionsAreNotCountedAsFailedPasswords() throws Exception {
        String email = "bp-" + UUID.randomUUID() + "@doro.local";
        UUID userId = authService.signup(new SignUpRequest(email, "Password123!", "Backpressure"));

        // 실패 상한(5)에 닿지 않도록 4개만 동시에 보낸다. 동시 1개·줄 0개라 한 번에 하나만 해싱하고 나머지는 거절된다.
        int callers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ErrorCode>> futures = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                try {
                    authService.login(new LoginRequest(email, "Wrong!Password1", "bp"), "127.0.0.1", "UA");
                    return null;
                } catch (AuthException e) {
                    return e.getErrorCode();
                }
            }));
        }
        go.countDown();
        int invalid = 0;
        int busy = 0;
        for (Future<ErrorCode> f : futures) {
            ErrorCode code = f.get(60, TimeUnit.SECONDS);
            if (code == ErrorCode.INVALID_CREDENTIALS) invalid++;
            if (code == ErrorCode.SERVER_BUSY) busy++;
        }
        pool.shutdown();

        assertThat(invalid + busy).as("모든 요청이 401 또는 503 으로 끝난다").isEqualTo(callers);
        assertThat(busy).as("동시 1개·줄 0개이므로 일부는 혼잡으로 거절된다").isGreaterThanOrEqualTo(1);
        assertThat(tracker.recentPairFailures(userId, "UNKNOWN"))   // 요청 컨텍스트 없이 서비스를 직접 부르면 IP 는 UNKNOWN
                .as("실패 횟수는 실제로 비밀번호가 틀린 횟수만큼만 올라간다(거절된 요청은 합산하지 않는다)")
                .isEqualTo(invalid);
    }

    @Test
    @DisplayName("혼잡 거절 뒤에도 정상 로그인은 성공한다 (계정이 잠기지 않았다)")
    void loginStillWorksAfterRejections() {
        String email = "bp2-" + UUID.randomUUID() + "@doro.local";
        authService.signup(new SignUpRequest(email, "Password123!", "Backpressure2"));
        assertThat(authService.login(new LoginRequest(email, "Password123!", "bp"), "127.0.0.1", "UA").requires2fa()).isFalse();
    }
}

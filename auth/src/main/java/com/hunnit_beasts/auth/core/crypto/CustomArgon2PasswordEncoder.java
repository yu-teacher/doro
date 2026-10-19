package com.hunnit_beasts.auth.core.crypto;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class CustomArgon2PasswordEncoder implements PasswordEncoder {

    /** 동시 해싱 수의 기본 상한. 해싱 1회가 64MB 를 쓰므로 8개면 약 512MB. */
    static final int DEFAULT_MAX_CONCURRENCY = 8;
    /** 해싱 자리를 기다리는 요청의 기본 상한. 이보다 많이 몰리면 기다리지 않고 바로 거절한다. */
    static final int DEFAULT_MAX_QUEUE = 32;
    /** 줄을 서서 기다리는 최대 시간(ms). 줄 상한이 주된 방어이고 이것은 보조다. */
    static final long DEFAULT_MAX_WAIT_MS = 3000;

    /** 운영 기본값: OWASP 권장 Argon2id 설정(saltLength 16B, hashLength 32B, parallelism 1, 메모리 64MB, 반복 3회). */
    static final int DEFAULT_MEMORY_KB = 65536;
    static final int DEFAULT_ITERATIONS = 3;
    /** 이보다 약한 값은 test 프로필이 아니면 받지 않는다(OWASP 가 허용하는 Argon2id 최소선: 19MiB·2회). */
    static final int MIN_MEMORY_KB = 19456;
    static final int MIN_ITERATIONS = 2;

    private final Argon2PasswordEncoder delegate;

    /**
     * 동시에 실행할 수 있는 해싱 수를 제한한다. 제한이 없으면 동시 로그인 N건이 N×64MB 를 한꺼번에 잡아
     * 힙이 바닥나(OutOfMemoryError) 요청이 500 으로 죽는다.
     */
    private final Semaphore permits;
    private final int maxQueue;
    private final long maxWaitMs;
    /** 지금 해싱 자리를 기다리는 요청 수. */
    private final AtomicInteger waiting = new AtomicInteger();

    /** 운영 기본 설정(메모리 64MB·3회). 비용을 바꾸지 않는 쪽의 시험이 쓴다. */
    public CustomArgon2PasswordEncoder() {
        this(DEFAULT_MAX_CONCURRENCY, DEFAULT_MAX_QUEUE, DEFAULT_MAX_WAIT_MS);
    }

    public CustomArgon2PasswordEncoder(int maxConcurrency, int maxQueue, long maxWaitMs) {
        this(maxConcurrency, maxQueue, maxWaitMs, DEFAULT_MEMORY_KB, DEFAULT_ITERATIONS, false);
    }

    /**
     * 해시 비용(메모리·반복 횟수)은 설정({@code doro.iam.password-hash.memory-kb}, {@code .iterations})으로 바꿀 수 있고 기본값은 운영 값이다.
     * 운영이 약한 값으로 뜨는 실수를 막기 위해, OWASP 최소선보다 약한 값은 {@code test} 프로필에서만 받는다(그 외에는 기동 실패).
     * 저장된 해시에는 만들 때의 매개변수가 들어 있어 검증은 이 설정과 무관하게 기존 해시를 그대로 확인한다.
     */
    @Autowired
    public CustomArgon2PasswordEncoder(
            @Value("${doro.iam.password-hash.max-concurrency:" + DEFAULT_MAX_CONCURRENCY + "}") int maxConcurrency,
            @Value("${doro.iam.password-hash.max-queue:" + DEFAULT_MAX_QUEUE + "}") int maxQueue,
            @Value("${doro.iam.password-hash.max-wait-ms:" + DEFAULT_MAX_WAIT_MS + "}") long maxWaitMs,
            @Value("${doro.iam.password-hash.memory-kb:" + DEFAULT_MEMORY_KB + "}") int memoryKb,
            @Value("${doro.iam.password-hash.iterations:" + DEFAULT_ITERATIONS + "}") int iterations,
            Environment environment) {
        this(maxConcurrency, maxQueue, maxWaitMs, memoryKb, iterations, environment.acceptsProfiles(Profiles.of("test")));
    }

    private CustomArgon2PasswordEncoder(int maxConcurrency, int maxQueue, long maxWaitMs, int memoryKb, int iterations, boolean weakAllowed) {
        boolean weak = memoryKb < MIN_MEMORY_KB || iterations < MIN_ITERATIONS;
        if (weak && !weakAllowed) {
            throw new IllegalStateException("doro.iam.password-hash 의 비용이 너무 약하다(memory-kb=" + memoryKb + ", iterations=" + iterations
                    + "). 최소 " + MIN_MEMORY_KB + "KB·" + MIN_ITERATIONS + "회이며, 더 약한 값은 test 프로필에서만 쓸 수 있다.");
        }
        if (weak) {
            LoggerFactory.getLogger(CustomArgon2PasswordEncoder.class)
                    .warn("Argon2 비용을 낮춰 사용 중(memory-kb={}, iterations={}) — test 프로필 전용", memoryKb, iterations);
        }
        this.delegate = new Argon2PasswordEncoder(16, 32, 1, memoryKb, iterations);
        this.permits = new Semaphore(Math.max(1, maxConcurrency), true);
        this.maxQueue = Math.max(0, maxQueue);
        this.maxWaitMs = Math.max(0, maxWaitMs);
    }

    @Override
    public String encode(CharSequence rawPassword) {
        acquire();
        try {
            return delegate.encode(rawPassword);
        } finally {
            permits.release();
        }
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        acquire();
        try {
            return delegate.matches(rawPassword, encodedPassword);
        } finally {
            permits.release();
        }
    }

    /**
     * 해싱 자리를 얻는다. 자리가 없으면 줄을 서되, 줄이 가득 차 있거나 너무 오래 기다리면 SERVER_BUSY 로 거절한다.
     * 줄 상한이 없으면 폭주 시 대기 중인 요청이 서버 스레드(기본 200)를 전부 차지해, 해싱과 무관한 요청까지 멈춘다.
     * 이 거절은 비밀번호가 틀렸다는 뜻이 아니므로 호출자는 실패 횟수를 올리면 안 된다(예외가 그대로 전파된다).
     */
    private void acquire() {
        try {
            if (permits.tryAcquire(0, TimeUnit.MILLISECONDS)) {
                return;
            }
            if (waiting.incrementAndGet() > maxQueue) {
                waiting.decrementAndGet();
                throw new AuthException(ErrorCode.SERVER_BUSY);
            }
            try {
                if (!permits.tryAcquire(maxWaitMs, TimeUnit.MILLISECONDS)) {
                    throw new AuthException(ErrorCode.SERVER_BUSY);
                }
            } finally {
                waiting.decrementAndGet();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthException(ErrorCode.SERVER_BUSY);
        }
    }
}

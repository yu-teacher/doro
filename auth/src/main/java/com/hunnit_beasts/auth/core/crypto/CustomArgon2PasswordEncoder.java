package com.hunnit_beasts.auth.core.crypto;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
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

    // OWASP 권장 Argon2id 매개변수 설정
    // saltLength: 16 bytes, hashLength: 32 bytes, parallelism: 1, memory: 65536 KB (64MB), iterations: 3
    private final Argon2PasswordEncoder delegate = new Argon2PasswordEncoder(
            16,
            32,
            1,
            65536,
            3
    );

    /**
     * 동시에 실행할 수 있는 해싱 수를 제한한다. 제한이 없으면 동시 로그인 N건이 N×64MB 를 한꺼번에 잡아
     * 힙이 바닥나(OutOfMemoryError) 요청이 500 으로 죽는다.
     */
    private final Semaphore permits;
    private final int maxQueue;
    private final long maxWaitMs;
    /** 지금 해싱 자리를 기다리는 요청 수. */
    private final AtomicInteger waiting = new AtomicInteger();

    public CustomArgon2PasswordEncoder() {
        this(DEFAULT_MAX_CONCURRENCY, DEFAULT_MAX_QUEUE, DEFAULT_MAX_WAIT_MS);
    }

    @Autowired
    public CustomArgon2PasswordEncoder(
            @Value("${doro.iam.password-hash.max-concurrency:" + DEFAULT_MAX_CONCURRENCY + "}") int maxConcurrency,
            @Value("${doro.iam.password-hash.max-queue:" + DEFAULT_MAX_QUEUE + "}") int maxQueue,
            @Value("${doro.iam.password-hash.max-wait-ms:" + DEFAULT_MAX_WAIT_MS + "}") long maxWaitMs) {
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

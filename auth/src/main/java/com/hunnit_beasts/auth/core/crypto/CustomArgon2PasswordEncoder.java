package com.hunnit_beasts.auth.core.crypto;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

@Component
public class CustomArgon2PasswordEncoder implements PasswordEncoder {

    /** 동시 해싱 수의 기본 상한. 해싱 1회가 64MB 를 쓰므로 8개면 약 512MB. */
    static final int DEFAULT_MAX_CONCURRENCY = 8;

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
     * 힙이 바닥나(OutOfMemoryError) 요청이 500 으로 죽는다. 초과분은 서버가 죽는 대신 줄을 서서 기다린다.
     */
    private final Semaphore permits;

    public CustomArgon2PasswordEncoder() {
        this(DEFAULT_MAX_CONCURRENCY);
    }

    @Autowired
    public CustomArgon2PasswordEncoder(
            @Value("${doro.iam.password-hash.max-concurrency:" + DEFAULT_MAX_CONCURRENCY + "}") int maxConcurrency) {
        this.permits = new Semaphore(Math.max(1, maxConcurrency), true);
    }

    @Override
    public String encode(CharSequence rawPassword) {
        permits.acquireUninterruptibly();
        try {
            return delegate.encode(rawPassword);
        } finally {
            permits.release();
        }
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        permits.acquireUninterruptibly();
        try {
            return delegate.matches(rawPassword, encodedPassword);
        } finally {
            permits.release();
        }
    }
}

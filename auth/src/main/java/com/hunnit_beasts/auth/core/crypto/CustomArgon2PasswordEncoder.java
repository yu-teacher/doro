package com.hunnit_beasts.auth.core.crypto;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class CustomArgon2PasswordEncoder implements PasswordEncoder {

    // OWASP 권장 Argon2id 매개변수 설정
    // saltLength: 16 bytes, hashLength: 32 bytes, parallelism: 1, memory: 65536 KB (64MB), iterations: 3
    private final Argon2PasswordEncoder delegate = new Argon2PasswordEncoder(
            16,
            32,
            1,
            65536,
            3
    );

    @Override
    public String encode(CharSequence rawPassword) {
        return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return delegate.matches(rawPassword, encodedPassword);
    }
}

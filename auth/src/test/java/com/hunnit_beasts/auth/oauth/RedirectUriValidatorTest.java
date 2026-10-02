package com.hunnit_beasts.auth.oauth;

import com.hunnit_beasts.auth.common.log.RateLimitedLogGate;
import com.hunnit_beasts.auth.domain.oauth.service.RedirectUriValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class RedirectUriValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://app.example/cb",
            "https://app.example:8443/cb?tenant=a",
            "http://localhost:3000/cb",
            "http://127.0.0.1/cb",
            "http://[::1]:8080/cb"
    })
    @DisplayName("https 와 loopback 의 http 는 통과")
    void accepted(String uri) {
        assertThat(RedirectUriValidator.validate(uri)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://app.example/cb", "http://localhost.evil.example/cb", "https://app.example/cb#x", "https://u:p@app.example/cb",
            "https://app.example/*", "https://*.app.example/cb", "https://app.example/a/./b", "https://app.example/a/%2e%2e/b",
            "https://app.example/a%2fb", "com.app://callback", "//app.example/cb", "app.example/cb", "https://app.example\\@evil.example", " https://app.example/cb"
    })
    @DisplayName("http(비 loopback)·fragment·userinfo·와일드카드·상대 세그먼트·비 http 스킴은 거부")
    void rejected(String uri) {
        assertThat(RedirectUriValidator.validate(uri)).isPresent();
    }

    @Test
    @DisplayName("null/빈 값은 거부, 오류 설명에 입력 원문이 들어가지 않는다")
    void nullAndEcho() {
        assertThat(RedirectUriValidator.validate(null)).isPresent();
        assertThat(RedirectUriValidator.validate("")).isPresent();
        assertThat(RedirectUriValidator.validate("http://evil-secret.example/cb").orElseThrow()).doesNotContain("evil-secret");
    }

    @Test
    @DisplayName("RateLimitedLogGate: 같은 키는 간격 안에서 한 번만 통과하고 간격이 지나면 다시 통과한다")
    void logGate() throws Exception {
        RateLimitedLogGate gate = new RateLimitedLogGate(60);
        Method m = RateLimitedLogGate.class.getDeclaredMethod("tryAcquire", String.class, long.class);
        m.setAccessible(true);
        long t0 = 1_000_000L;
        assertThat((boolean) m.invoke(gate, "k", t0)).isTrue();
        assertThat((boolean) m.invoke(gate, "k", t0 + 59_000)).isFalse();
        assertThat((boolean) m.invoke(gate, "other", t0 + 1_000)).isTrue();
        assertThat((boolean) m.invoke(gate, "k", t0 + 60_000)).isTrue();
    }
}

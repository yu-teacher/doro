package com.hunnit_beasts.guard.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RateLimitedWarnTest {

    @Test
    @DisplayName("같은 키의 경고는 간격 안에서 한 번만 기록되고, 다른 키는 각각 기록된다")
    void logsOncePerKeyPerInterval() {
        Logger log = mock(Logger.class);
        RateLimitedWarn warn = new RateLimitedWarn(60_000L);

        for (int i = 0; i < 100; i++) {
            warn.warn(log, "rest:/check", "no token: {}", "x");
        }
        warn.warn(log, "rest:/tuples", "no token: {}", "y");

        verify(log, times(2)).warn(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("간격이 지나면 다시 기록되고 억제된 건수가 함께 전달된다")
    void logsAgainAfterInterval() throws Exception {
        Logger log = mock(Logger.class);
        RateLimitedWarn warn = new RateLimitedWarn(50L);

        warn.warn(log, "k", "m");
        warn.warn(log, "k", "m");
        Thread.sleep(80);
        warn.warn(log, "k", "m");

        verify(log, times(2)).warn(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("서로 다른 키가 아무리 많아도 추적 키 수는 상한을 넘지 않는다")
    void trackedKeysAreBounded() {
        Logger log = mock(Logger.class);
        RateLimitedWarn warn = new RateLimitedWarn(60_000L, 100);

        for (int i = 0; i < 10_000; i++) {
            warn.warn(log, "rest:GET:/api/v1/guard/random-" + i, "no token");
            org.assertj.core.api.Assertions.assertThat(warn.trackedKeys()).isLessThanOrEqualTo(100);
        }
        // 상한에 걸려 키가 비워져도 새 키의 첫 경고는 기록된다 (모든 키가 한 번은 기록됨)
        verify(log, times(10_000)).warn(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("ServiceTokenFilter 의 로그 키는 경로 앞 4개 세그먼트로 제한된다")
    void filterLogKeyIsTruncatedToFourSegments() {
        org.assertj.core.api.Assertions.assertThat(ServiceTokenFilter.boundedPath("/api/v1/guard/tuples/abc/def"))
                .isEqualTo("/api/v1/guard/tuples");
        org.assertj.core.api.Assertions.assertThat(ServiceTokenFilter.boundedPath("/api/v1/guard/check"))
                .isEqualTo("/api/v1/guard/check");
        org.assertj.core.api.Assertions.assertThat(ServiceTokenFilter.boundedPath("/api/v1"))
                .isEqualTo("/api/v1");
    }
}

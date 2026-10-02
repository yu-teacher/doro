package com.hunnit_beasts.auth.common.log;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 같은 종류의 경고 로그가 공격/오설정으로 폭주하지 않도록 키별로 일정 간격에 한 번만 통과시킨다.
 * (예: 재사용된 인가 코드, 레지스트리에 없는 client_id) 키에는 비밀값이 아닌 분류 문자열만 쓴다.
 */
@Component
public class RateLimitedLogGate {

    /** 추적하는 키 수의 상한. 넘으면 오래된 항목을 정리한다. */
    private static final int MAX_TRACKED_KEYS = 1000;

    private final Map<String, Long> lastLoggedMillis = new ConcurrentHashMap<>();
    private final long intervalMillis;

    public RateLimitedLogGate(@Value("${doro.oauth.warn-log-interval-seconds:60}") long intervalSeconds) {
        this.intervalMillis = Math.max(0, intervalSeconds) * 1000;
    }

    /** @return 지금 로그를 남겨도 되면 true */
    public boolean tryAcquire(String key) {
        return tryAcquire(key, System.currentTimeMillis());
    }

    boolean tryAcquire(String key, long nowMillis) {
        if (lastLoggedMillis.size() >= MAX_TRACKED_KEYS) {
            lastLoggedMillis.entrySet().removeIf(e -> nowMillis - e.getValue() >= intervalMillis);
        }
        boolean[] allowed = {false};
        lastLoggedMillis.compute(key, (k, last) -> {
            if (last == null || nowMillis - last >= intervalMillis) {
                allowed[0] = true;
                return nowMillis;
            }
            return last;
        });
        return allowed[0];
    }
}

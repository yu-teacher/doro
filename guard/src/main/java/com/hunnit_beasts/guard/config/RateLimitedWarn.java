package com.hunnit_beasts.guard.config;

import org.slf4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 같은 키의 경고 로그를 일정 간격마다 한 번만 남기고, 그 사이에 억제된 건수를 함께 알려준다.
 * (서비스 토큰을 아직 보내지 않는 호출자가 요청마다 로그를 폭주시키는 것을 막는다.)
 */
public class RateLimitedWarn {

    private static final long DEFAULT_INTERVAL_MILLIS = 60_000L;

    private final long intervalMillis;
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    public RateLimitedWarn() {
        this(DEFAULT_INTERVAL_MILLIS);
    }

    public RateLimitedWarn(long intervalMillis) {
        this.intervalMillis = intervalMillis;
    }

    public void warn(Logger log, String key, String message, Object... args) {
        long now = System.currentTimeMillis();
        State state = states.computeIfAbsent(key, k -> new State());
        long last = state.lastLogged.get();
        if (now - last >= intervalMillis && state.lastLogged.compareAndSet(last, now)) {
            long suppressed = state.suppressed.getAndSet(0);
            log.warn(message + " (suppressed {} similar in the last interval)", append(args, suppressed));
        } else {
            state.suppressed.incrementAndGet();
        }
    }

    private static Object[] append(Object[] args, long suppressed) {
        Object[] all = new Object[args.length + 1];
        System.arraycopy(args, 0, all, 0, args.length);
        all[args.length] = suppressed;
        return all;
    }

    private static final class State {
        private final AtomicLong lastLogged = new AtomicLong(0);
        private final AtomicLong suppressed = new AtomicLong(0);
    }
}

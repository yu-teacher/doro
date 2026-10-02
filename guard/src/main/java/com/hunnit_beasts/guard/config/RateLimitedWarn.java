package com.hunnit_beasts.guard.config;

import org.slf4j.Logger;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 같은 키의 경고 로그를 일정 간격마다 한 번만 남기고, 그 사이에 억제된 건수를 함께 알려준다.
 * (서비스 토큰을 아직 보내지 않는 호출자가 요청마다 로그를 폭주시키는 것을 막는다.)
 */
public class RateLimitedWarn {

    private static final long DEFAULT_INTERVAL_MILLIS = 60_000L;

    /** 추적하는 키의 상한. 호출자가 임의의 키(URI 등)를 만들어 내도 메모리가 무한히 늘지 않게 한다. */
    private static final int DEFAULT_MAX_KEYS = 1_000;

    private final long intervalMillis;
    private final int maxKeys;
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    public RateLimitedWarn() {
        this(DEFAULT_INTERVAL_MILLIS);
    }

    public RateLimitedWarn(long intervalMillis) {
        this(intervalMillis, DEFAULT_MAX_KEYS);
    }

    public RateLimitedWarn(long intervalMillis, int maxKeys) {
        this.intervalMillis = intervalMillis;
        this.maxKeys = Math.max(1, maxKeys);
    }

    /** 현재 추적 중인 키 수 (테스트/진단용). */
    public int trackedKeys() {
        return states.size();
    }

    public void warn(Logger log, String key, String message, Object... args) {
        long now = System.currentTimeMillis();
        State state = states.get(key);
        if (state == null) {
            if (states.size() >= maxKeys) {
                evict(now);
            }
            state = states.computeIfAbsent(key, k -> new State());
        }
        long last = state.lastLogged.get();
        if (now - last >= intervalMillis && state.lastLogged.compareAndSet(last, now)) {
            long suppressed = state.suppressed.getAndSet(0);
            log.warn(message + " (suppressed {} similar in the last interval)", append(args, suppressed));
        } else {
            state.suppressed.incrementAndGet();
        }
    }

    /** 상한에 닿으면 간격이 지난 키부터 지우고, 그래도 가득이면 가장 오래된 절반을 지운다. */
    private synchronized void evict(long now) {
        if (states.size() < maxKeys) {
            return;
        }
        states.entrySet().removeIf(e -> now - e.getValue().lastLogged.get() >= intervalMillis);
        if (states.size() >= maxKeys) {
            int toRemove = Math.max(1, states.size() / 2);
            states.entrySet().stream()
                    .sorted(Comparator.comparingLong((Map.Entry<String, State> e) -> e.getValue().lastLogged.get()))
                    .limit(toRemove)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(states::remove);
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

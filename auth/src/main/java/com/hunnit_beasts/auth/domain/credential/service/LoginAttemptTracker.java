package com.hunnit_beasts.auth.domain.credential.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 로그인 실패 계수기. 계정 잠금을 두 겹으로 건다.
 * <ul>
 *   <li><b>(계정, IP) 쌍</b>: 쌍 윈도우(기본 15분) 안에 pairMax(5)번 틀리면 그 IP 만 pairLock(15분) 동안 막는다.
 *       다른 IP 의 본인은 영향이 없으므로, 이메일만 알아서는 남의 계정을 잠글 수 없다.</li>
 *   <li><b>계정 전체</b>: 여러 IP 의 실패를 합쳐 계정 윈도우(기본 1시간) 안에 accountMax(30)번이면 계정을 accountLock(1시간) 잠근다.
 *       쌍이 잠긴 동안의 시도는 합산에 넣지 않으므로 IP 하나로는 시간당 최대 20번이라 이 한도에 닿지 못한다
 *       (분산 무차별 대입을 늦추는 용도).</li>
 * </ul>
 * 상태는 메모리에만 둔다(단일 인스턴스). 재시작하면 카운터가 초기화되지만 계정 전체 잠금은 DB 에 남는다.
 * 다중 인스턴스가 되면 Redis 로 옮긴다. 쌍 항목 수에는 상한을 둬 IPv6 주소를 바꿔 가며 메모리를 채우는 것을 막는다.
 */
@Slf4j
@Component
public class LoginAttemptTracker {

    public enum Result {
        /** 실패를 셌고 아직 한도 미만 */
        COUNTED,
        /** 이 실패로 (계정, IP) 쌍이 잠겼다 */
        PAIR_LOCKED,
        /** 이 실패로 계정 전체 잠금 한도에 닿았다(호출 측이 DB 에 잠금을 기록한다) */
        ACCOUNT_LOCKED,
        /** 이미 잠긴 쌍의 시도라 세지 않았다 */
        IGNORED
    }

    /** 쌍 항목 수 상한. 넘으면 만료된 항목을 먼저 치우고, 그래도 가득 차면 새 쌍은 추적하지 않는다(계정 합산은 계속한다). */
    static final int MAX_PAIR_ENTRIES = 100_000;

    private static final class Pair {
        final ArrayDeque<Long> failures = new ArrayDeque<>();
        long lockedUntil;
    }

    private final ConcurrentHashMap<String, Pair> pairs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ArrayDeque<Long>> accounts = new ConcurrentHashMap<>();
    private final LongSupplier nowMillis;
    private final int pairMax;
    private final long pairWindowMillis;
    private final long pairLockMillis;
    private final int accountMax;
    private final long accountWindowMillis;
    private final long accountLockMillis;

    @Autowired
    public LoginAttemptTracker(
            @Value("${doro.iam.lockout.pair-max-failures:5}") int pairMax,
            @Value("${doro.iam.lockout.pair-window-minutes:15}") long pairWindowMinutes,
            @Value("${doro.iam.lockout.pair-lock-minutes:15}") long pairLockMinutes,
            @Value("${doro.iam.lockout.account-max-failures:30}") int accountMax,
            @Value("${doro.iam.lockout.account-window-minutes:60}") long accountWindowMinutes,
            @Value("${doro.iam.lockout.account-lock-minutes:60}") long accountLockMinutes) {
        this(System::currentTimeMillis, pairMax, Duration.ofMinutes(pairWindowMinutes), Duration.ofMinutes(pairLockMinutes),
                accountMax, Duration.ofMinutes(accountWindowMinutes), Duration.ofMinutes(accountLockMinutes));
    }

    /** 시계를 주입할 수 있는 생성자(시간이 흐르는 동작을 시험하기 위해). */
    public LoginAttemptTracker(LongSupplier nowMillis, int pairMax, Duration pairWindow, Duration pairLock,
                               int accountMax, Duration accountWindow, Duration accountLock) {
        this.nowMillis = nowMillis;
        this.pairMax = pairMax;
        this.pairWindowMillis = pairWindow.toMillis();
        this.pairLockMillis = pairLock.toMillis();
        this.accountMax = accountMax;
        this.accountWindowMillis = accountWindow.toMillis();
        this.accountLockMillis = accountLock.toMillis();
    }

    public int accountMax() {
        return accountMax;
    }

    public Duration accountLock() {
        return Duration.ofMillis(accountLockMillis);
    }

    private static String key(UUID userId, String ip) {
        return userId + "|" + ip;
    }

    /** 이 (계정, IP) 쌍이 지금 잠겨 있는가. */
    public boolean isPairLocked(UUID userId, String ip) {
        Pair pair = pairs.get(key(userId, ip));
        if (pair == null) {
            return false;
        }
        synchronized (pair) {
            return pair.lockedUntil > nowMillis.getAsLong();
        }
    }

    public Result recordFailure(UUID userId, String ip) {
        long now = nowMillis.getAsLong();
        boolean pairLockedNow = false;

        Pair pair = pairFor(userId, ip);
        if (pair != null) {
            synchronized (pair) {
                if (pair.lockedUntil > now) {
                    return Result.IGNORED;
                }
                prune(pair.failures, now - pairWindowMillis);
                pair.failures.addLast(now);
                if (pair.failures.size() >= pairMax) {
                    pair.lockedUntil = now + pairLockMillis;
                    pair.failures.clear();
                    pairLockedNow = true;
                    log.warn("Login lock: (account, IP) pair locked: userId={}, ip={}, minutes={}",
                            userId, ip, pairLockMillis / 60_000);
                }
            }
        }

        ArrayDeque<Long> window = accounts.computeIfAbsent(userId, id -> new ArrayDeque<>());
        synchronized (window) {
            prune(window, now - accountWindowMillis);
            window.addLast(now);
            if (window.size() >= accountMax) {
                window.clear();
                log.warn("SECURITY: account-wide login lock reached by distributed failures: userId={}, failures>={}", userId, accountMax);
                return Result.ACCOUNT_LOCKED;
            }
        }
        return pairLockedNow ? Result.PAIR_LOCKED : Result.COUNTED;
    }

    /** 이 (계정, IP) 쌍이 현재 창 안에서 센 실패 수. 진단과 시험용이다. */
    public int recentPairFailures(UUID userId, String ip) {
        Pair pair = pairs.get(key(userId, ip));
        if (pair == null) {
            return 0;
        }
        synchronized (pair) {
            prune(pair.failures, nowMillis.getAsLong() - pairWindowMillis);
            return pair.failures.size();
        }
    }

    /** 로그인에 성공하면 그 (계정, IP) 쌍의 실패만 지운다. 계정 합산은 지우지 않는다(공격자가 쌓은 실패를 본인 로그인이 없애지 못하게). */
    public void recordSuccess(UUID userId, String ip) {
        pairs.remove(key(userId, ip));
    }

    /** 관리자 잠금 해제·정지 해제: 이 계정의 쌍 잠금과 계정 합산을 모두 초기화한다. */
    public void clearAccount(UUID userId) {
        accounts.remove(userId);
        String prefix = userId + "|";
        pairs.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** 만료된 항목 정리. 이미 풀렸고 윈도우 안에 남은 실패도 없는 쌍과 빈 계정 창을 지운다. */
    @Scheduled(fixedDelayString = "${doro.iam.lockout.purge-interval-ms:300000}")
    public void purgeExpired() {
        long now = nowMillis.getAsLong();
        pairs.entrySet().removeIf(entry -> {
            Pair p = entry.getValue();
            synchronized (p) {
                prune(p.failures, now - pairWindowMillis);
                return p.lockedUntil <= now && p.failures.isEmpty();
            }
        });
        accounts.entrySet().removeIf(entry -> {
            ArrayDeque<Long> w = entry.getValue();
            synchronized (w) {
                prune(w, now - accountWindowMillis);
                return w.isEmpty();
            }
        });
    }

    int pairEntryCount() {
        return pairs.size();
    }

    private Pair pairFor(UUID userId, String ip) {
        String k = key(userId, ip);
        Pair existing = pairs.get(k);
        if (existing != null) {
            return existing;
        }
        if (pairs.size() >= MAX_PAIR_ENTRIES) {
            purgeExpired();
            if (pairs.size() >= MAX_PAIR_ENTRIES) {
                return null;
            }
        }
        return pairs.computeIfAbsent(k, x -> new Pair());
    }

    private static void prune(ArrayDeque<Long> times, long cutoff) {
        while (!times.isEmpty() && times.peekFirst() <= cutoff) {
            times.pollFirst();
        }
    }
}

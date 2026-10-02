package com.hunnit_beasts.auth.domain.oauth.store;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 단일 인스턴스 메모리 저장소(Redis 가 없는 로컬/테스트용 폴백). 키는 코드의 SHA-256 해시다. */
@Slf4j
public class InMemoryAuthorizationCodeStore implements AuthorizationCodeStore {

    private record Entry(AuthorizationCodeData data, Instant expiresAt) {}

    private final Map<String, Entry> pending = new ConcurrentHashMap<>();
    /** 소비된 코드의 해시와 코드 만료 시각. 재사용 시도를 탐지하는 데만 쓴다. */
    private final Map<String, Instant> consumed = new ConcurrentHashMap<>();

    private final Duration ttl;
    private final int maxPending;
    private final Clock clock;

    public InMemoryAuthorizationCodeStore(Duration ttl, int maxPending, Clock clock) {
        this.ttl = ttl;
        this.maxPending = maxPending;
        this.clock = clock;
    }

    @Override
    public void save(String code, AuthorizationCodeData data) {
        requireCapacity();
        pending.put(AuthorizationCodeStore.hash(code), new Entry(data, clock.instant().plus(ttl)));
    }

    @Override
    public Optional<AuthorizationCodeData> consume(String code) {
        String key = AuthorizationCodeStore.hash(code);
        Entry entry = pending.remove(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (clock.instant().isAfter(entry.expiresAt())) {
            return Optional.empty();
        }
        consumed.put(key, entry.expiresAt());
        return Optional.of(entry.data());
    }

    @Override
    public boolean wasConsumed(String code) {
        Instant until = consumed.get(AuthorizationCodeStore.hash(code));
        return until != null && !clock.instant().isAfter(until);
    }

    @Override
    public int purgeExpired(Instant now) {
        int before = pending.size();
        pending.values().removeIf(e -> now.isAfter(e.expiresAt()));
        consumed.values().removeIf(now::isAfter);
        int removed = before - pending.size();
        if (removed > 0) {
            log.debug("Purged {} expired authorization code(s)", removed);
        }
        return removed;
    }

    @Override
    public int pendingCount() {
        return pending.size();
    }

    private void requireCapacity() {
        if (maxPending <= 0 || pending.size() < maxPending) {
            return;
        }
        purgeExpired(clock.instant());
        if (pending.size() >= maxPending) {
            log.warn("Pending authorization code capacity reached: limit={}", maxPending);
            throw new AuthException(ErrorCode.TOO_MANY_REQUESTS);
        }
    }
}

package com.hunnit_beasts.auth.domain.oauth.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Redis 저장소. 키는 {@code doro:oauth:code:} + 코드의 SHA-256 hex, 값은 {@link AuthorizationCodeData} JSON, TTL 로 만료된다.
 * 소비는 GETDEL 로 원자적이라 동시에 두 번 교환해도 한 요청만 성공한다. 재사용 탐지를 위해 소비한 코드의
 * 해시만 별도 키({@code doro:oauth:code-used:})에 코드 TTL 동안 남긴다.
 */
@Slf4j
public class RedisAuthorizationCodeStore implements AuthorizationCodeStore {

    static final String CODE_KEY_PREFIX = "doro:oauth:code:";
    static final String USED_KEY_PREFIX = "doro:oauth:code-used:";
    private static final String USED_MARKER = "1";

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RedisAuthorizationCodeStore(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public void save(String code, AuthorizationCodeData data) {
        try {
            redis.opsForValue().set(CODE_KEY_PREFIX + AuthorizationCodeStore.hash(code),
                    objectMapper.writeValueAsString(data), ttl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize authorization code payload", e);
        }
    }

    @Override
    public Optional<AuthorizationCodeData> consume(String code) {
        String hash = AuthorizationCodeStore.hash(code);
        String json = redis.opsForValue().getAndDelete(CODE_KEY_PREFIX + hash);
        if (json == null) {
            return Optional.empty();
        }
        redis.opsForValue().set(USED_KEY_PREFIX + hash, USED_MARKER, ttl);
        try {
            return Optional.of(objectMapper.readValue(json, AuthorizationCodeData.class));
        } catch (JsonProcessingException e) {
            log.error("Stored authorization code payload is unreadable", e);
            return Optional.empty();
        }
    }

    @Override
    public boolean wasConsumed(String code) {
        return Boolean.TRUE.equals(redis.hasKey(USED_KEY_PREFIX + AuthorizationCodeStore.hash(code)));
    }

    @Override
    public int purgeExpired(Instant now) {
        return 0; // TTL 로 Redis 가 직접 만료시킨다.
    }

    @Override
    public int pendingCount() {
        return -1;
    }
}

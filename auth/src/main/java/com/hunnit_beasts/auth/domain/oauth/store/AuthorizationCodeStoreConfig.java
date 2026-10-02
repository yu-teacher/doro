package com.hunnit_beasts.auth.domain.oauth.store;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;

/**
 * 인가 코드 저장소 선택.
 * <ul>
 *   <li>{@code redis}: Redis 고정(연결 불가 시 요청 시점에 오류)</li>
 *   <li>{@code memory}: 메모리 고정(테스트/로컬)</li>
 *   <li>{@code auto}(기본): Redis 에 연결되면 Redis, 아니면 WARN 후 메모리</li>
 * </ul>
 */
@Slf4j
@Configuration
public class AuthorizationCodeStoreConfig {

    @Bean
    public AuthorizationCodeStore authorizationCodeStore(
            ObjectProvider<StringRedisTemplate> redisProvider,
            @Value("${doro.oauth.code-store:auto}") String mode,
            @Value("${doro.oauth.code-ttl-seconds:300}") long ttlSeconds,
            @Value("${doro.oauth.max-pending-authorization-codes:10000}") int maxPending) {
        Duration ttl = Duration.ofSeconds(ttlSeconds);
        String normalized = mode == null ? "auto" : mode.trim().toLowerCase();
        StringRedisTemplate redis = redisProvider.getIfAvailable();

        switch (normalized) {
            case "memory" -> {
                return memory(ttl, maxPending, "configured");
            }
            case "redis" -> {
                if (redis == null) {
                    throw new IllegalStateException("doro.oauth.code-store=redis requires a Redis connection");
                }
                log.info("OAuth authorization codes are stored in Redis (ttl={}s)", ttlSeconds);
                return new RedisAuthorizationCodeStore(redis, ttl);
            }
            case "auto" -> {
                if (redis != null && isReachable(redis)) {
                    log.info("OAuth authorization codes are stored in Redis (ttl={}s)", ttlSeconds);
                    return new RedisAuthorizationCodeStore(redis, ttl);
                }
                log.warn("Redis is not available; OAuth authorization codes fall back to the in-memory store");
                return memory(ttl, maxPending, "fallback");
            }
            default -> throw new IllegalStateException("Unsupported doro.oauth.code-store: " + mode);
        }
    }

    private static boolean isReachable(StringRedisTemplate redis) {
        try {
            var factory = redis.getConnectionFactory();
            if (factory == null) {
                return false;
            }
            try (var connection = factory.getConnection()) {
                connection.ping();
                return true;
            }
        } catch (RuntimeException e) {
            log.warn("Redis connectivity probe failed: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    private static AuthorizationCodeStore memory(Duration ttl, int maxPending, String reason) {
        log.info("OAuth authorization codes are stored in memory ({})", reason);
        return new InMemoryAuthorizationCodeStore(ttl, maxPending, Clock.systemUTC());
    }
}

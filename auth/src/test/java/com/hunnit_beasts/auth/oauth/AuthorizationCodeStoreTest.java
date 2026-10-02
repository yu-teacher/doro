package com.hunnit_beasts.auth.oauth;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.domain.oauth.store.AuthorizationCodeData;
import com.hunnit_beasts.auth.domain.oauth.store.AuthorizationCodeStore;
import com.hunnit_beasts.auth.domain.oauth.store.InMemoryAuthorizationCodeStore;
import com.hunnit_beasts.auth.domain.oauth.store.RedisAuthorizationCodeStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 인가 코드 저장소(메모리/Redis) 단위 테스트. */
class AuthorizationCodeStoreTest {

    private static final Duration TTL = Duration.ofSeconds(300);

    private static AuthorizationCodeData data() {
        return new AuthorizationCodeData(UUID.randomUUID(), "client", "https://good.example/cb", "challenge", "openid", "nonce", 1_700_000_000L);
    }

    /** 수동으로 시간을 흘려보내는 Clock. */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

        void advance(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    @Test
    @DisplayName("메모리: 1회 소비 후 재소비는 비어 있고 wasConsumed 가 재사용을 알려 준다")
    void memorySingleUse() {
        InMemoryAuthorizationCodeStore store = new InMemoryAuthorizationCodeStore(TTL, 10, Clock.systemUTC());
        AuthorizationCodeData value = data();
        store.save("code-1", value);

        assertThat(store.wasConsumed("code-1")).isFalse();
        assertThat(store.consume("code-1")).contains(value);
        assertThat(store.consume("code-1")).isEmpty();
        assertThat(store.wasConsumed("code-1")).isTrue();
        assertThat(store.consume("never-issued")).isEmpty();
        assertThat(store.wasConsumed("never-issued")).isFalse();
    }

    @Test
    @DisplayName("메모리: TTL 이 지나면 소비할 수 없고, 만료분은 스위퍼가 제거한다")
    void memoryExpiry() {
        MutableClock clock = new MutableClock();
        InMemoryAuthorizationCodeStore store = new InMemoryAuthorizationCodeStore(TTL, 10, clock);
        store.save("expiring", data());
        store.save("sweep-me", data());

        clock.advance(TTL.plusSeconds(1));
        assertThat(store.consume("expiring")).isEmpty();
        assertThat(store.pendingCount()).isEqualTo(1);
        assertThat(store.purgeExpired(clock.instant())).isEqualTo(1);
        assertThat(store.pendingCount()).isZero();
    }

    @Test
    @DisplayName("메모리: 상한에 도달하면 만료분을 정리한 뒤에도 가득 차 있을 때만 429 로 거부한다")
    void memoryCapacity() {
        MutableClock clock = new MutableClock();
        InMemoryAuthorizationCodeStore store = new InMemoryAuthorizationCodeStore(TTL, 2, clock);
        store.save("a", data());
        store.save("b", data());
        assertThatThrownBy(() -> store.save("c", data()))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS));

        clock.advance(TTL.plusSeconds(1));
        store.save("c", data());
        assertThat(store.pendingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("메모리: 코드 원문은 저장하지 않는다(해시 키)")
    @SuppressWarnings("unchecked")
    void memoryDoesNotKeepRawCode() throws Exception {
        InMemoryAuthorizationCodeStore store = new InMemoryAuthorizationCodeStore(TTL, 10, Clock.systemUTC());
        store.save("raw-secret-code", data());
        var field = InMemoryAuthorizationCodeStore.class.getDeclaredField("pending");
        field.setAccessible(true);
        Map<String, ?> pending = (Map<String, ?>) field.get(store);
        assertThat(pending.keySet()).containsExactly(AuthorizationCodeStore.hash("raw-secret-code"));
        assertThat(pending.keySet()).doesNotContain("raw-secret-code");
    }

    @Test
    @DisplayName("Redis: 키는 doro:oauth:code:+SHA-256 hex, TTL 300s, JSON 페이로드에 코드 원문이 없다")
    @SuppressWarnings("unchecked")
    void redisSaveUsesHashedKeyAndTtl() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        RedisAuthorizationCodeStore store = new RedisAuthorizationCodeStore(redis, TTL);

        AuthorizationCodeData value = data();
        store.save("raw-secret-code", value);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(ops).set(key.capture(), json.capture(), eq(TTL));
        assertThat(key.getValue()).isEqualTo("doro:oauth:code:" + AuthorizationCodeStore.hash("raw-secret-code"));
        assertThat(AuthorizationCodeStore.hash("raw-secret-code")).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(key.getValue()).doesNotContain("raw-secret-code");
        assertThat(json.getValue()).doesNotContain("raw-secret-code")
                .contains("\"userId\"", "\"clientId\"", "\"redirectUri\"", "\"codeChallenge\"", "\"scope\"", "\"nonce\"", "\"authTime\"");
    }

    @Test
    @DisplayName("Redis: GETDEL 로 원자적으로 소비하고, 소비 표식으로 재사용을 탐지한다")
    @SuppressWarnings("unchecked")
    void redisConsumeUsesGetDel() {
        Map<String, String> backing = new HashMap<>();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        org.mockito.Mockito.doAnswer(inv -> backing.put(inv.getArgument(0), inv.getArgument(1)))
                .when(ops).set(anyString(), anyString(), eq(TTL));
        when(ops.getAndDelete(anyString())).thenAnswer(inv -> backing.remove((String) inv.getArgument(0)));
        when(redis.hasKey(anyString())).thenAnswer(inv -> backing.containsKey((String) inv.getArgument(0)));
        RedisAuthorizationCodeStore store = new RedisAuthorizationCodeStore(redis, TTL);

        AuthorizationCodeData value = data();
        store.save("code-x", value);
        assertThat(store.consume("code-x")).contains(value);
        assertThat(store.consume("code-x")).isEmpty();
        assertThat(store.wasConsumed("code-x")).isTrue();
        assertThat(store.wasConsumed("other")).isFalse();
        verify(ops, org.mockito.Mockito.times(2)).getAndDelete("doro:oauth:code:" + AuthorizationCodeStore.hash("code-x"));
    }
}

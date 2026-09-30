package com.hunnit_beasts.auth.config;

import com.hunnit_beasts.auth.common.web.ClientIpResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 인증 관련 공개 엔드포인트(로그인/2FA 로그인/계정 조회/가입)에 IP 단위 요청 제한을 적용한다.
 * 계정 잠금은 계정 단위라서 대량 비밀번호 시도나 계정 열거를 막지 못하므로 IP 단위 제한을 함께 둔다.
 * 고정 윈도우 방식이며 단일 인스턴스 메모리 기준이다.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AuthRateLimitFilter extends OncePerRequestFilter {

    enum Bucket { LOGIN, LOOKUP, SIGNUP }

    private record Window(long startEpochSecond, int count) {}

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    private final boolean enabled;
    private final int loginMax;
    private final long loginWindowSeconds;
    private final int lookupMax;
    private final long lookupWindowSeconds;
    private final int signupMax;
    private final long signupWindowSeconds;

    public AuthRateLimitFilter(
            @Value("${doro.iam.rate-limit.enabled:true}") boolean enabled,
            @Value("${doro.iam.rate-limit.login-max:20}") int loginMax,
            @Value("${doro.iam.rate-limit.login-window-seconds:600}") long loginWindowSeconds,
            @Value("${doro.iam.rate-limit.lookup-max:30}") int lookupMax,
            @Value("${doro.iam.rate-limit.lookup-window-seconds:600}") long lookupWindowSeconds,
            @Value("${doro.iam.rate-limit.signup-max:10}") int signupMax,
            @Value("${doro.iam.rate-limit.signup-window-seconds:3600}") long signupWindowSeconds) {
        this.enabled = enabled;
        this.loginMax = loginMax;
        this.loginWindowSeconds = loginWindowSeconds;
        this.lookupMax = lookupMax;
        this.lookupWindowSeconds = lookupWindowSeconds;
        this.signupMax = signupMax;
        this.signupWindowSeconds = signupWindowSeconds;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || !"POST".equals(request.getMethod()) || bucketOf(request.getRequestURI()) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Bucket bucket = bucketOf(request.getRequestURI());
        String ip = ClientIpResolver.resolve(request);
        long retryAfter = tryAcquire(bucket, ip, Instant.now().getEpochSecond());
        if (retryAfter > 0) {
            log.warn("Rate limit exceeded: bucket={}, ip={}, retryAfterSeconds={}", bucket, ip, retryAfter);
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"status\":429,\"error\":\"Too Many Requests\",\"code\":\"TOO_MANY_REQUESTS\","
                    + "\"message\":\"요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /** 허용되면 0, 제한을 넘으면 다음 윈도우까지 남은 초를 반환한다. */
    long tryAcquire(Bucket bucket, String ip, long nowEpochSecond) {
        int max = maxOf(bucket);
        long windowSeconds = windowOf(bucket);
        long[] retryAfter = {0};
        windows.compute(bucket + ":" + ip, (key, current) -> {
            if (current == null || nowEpochSecond - current.startEpochSecond() >= windowSeconds) {
                return new Window(nowEpochSecond, 1);
            }
            if (current.count() >= max) {
                retryAfter[0] = Math.max(1, current.startEpochSecond() + windowSeconds - nowEpochSecond);
                return current;
            }
            return new Window(current.startEpochSecond(), current.count() + 1);
        });
        return retryAfter[0];
    }

    private static Bucket bucketOf(String uri) {
        return switch (uri) {
            case "/api/v1/auth/login", "/api/v1/auth/2fa/login" -> Bucket.LOGIN;
            case "/api/v1/auth/lookup" -> Bucket.LOOKUP;
            case "/api/v1/auth/signup" -> Bucket.SIGNUP;
            default -> null;
        };
    }

    private int maxOf(Bucket bucket) {
        return switch (bucket) {
            case LOGIN -> loginMax;
            case LOOKUP -> lookupMax;
            case SIGNUP -> signupMax;
        };
    }

    private long windowOf(Bucket bucket) {
        return switch (bucket) {
            case LOGIN -> loginWindowSeconds;
            case LOOKUP -> lookupWindowSeconds;
            case SIGNUP -> signupWindowSeconds;
        };
    }

    /** 만료된 윈도우를 정리해 메모리가 계속 늘어나지 않게 한다. */
    @Scheduled(fixedDelayString = "${doro.iam.rate-limit.cleanup-interval-ms:300000}")
    void evictExpiredWindows() {
        long now = Instant.now().getEpochSecond();
        long longest = Math.max(loginWindowSeconds, Math.max(lookupWindowSeconds, signupWindowSeconds));
        windows.entrySet().removeIf(entry -> now - entry.getValue().startEpochSecond() >= longest);
    }
}

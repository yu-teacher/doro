package com.hunnit_beasts.guard.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.util.Optional;

/**
 * REST 호출에 서비스 토큰 검증을 적용한다.
 *
 * <p>접두사가 일치하는 요청만 검사하는 방식은 {@code /%61pi/v1/guard/...} 같은 퍼센트 인코딩 경로가 원시 URI 비교를 빠져나가
 * 인증 없이 컨트롤러에 도달하게 만든다. 그래서 (1) 서버가 디코딩·정규화한 경로로 판단하고,
 * (2) 공개 허용 목록(health, swagger 등)에 없는 요청은 모두 검사한다.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class ServiceTokenFilter extends OncePerRequestFilter {

    /** 서비스 토큰 없이 접근할 수 있는 경로 접두사. 여기에 없는 경로는 모두 토큰이 필요하다. */
    private static final String[] PUBLIC_PREFIXES = {"/actuator", "/swagger-ui", "/v3/api-docs", "/error"};
    /** 경고 로그 키에 쓰는 경로 세그먼트 수. 원시 URI 를 키로 쓰면 임의 경로로 키가 무한히 늘어난다. */
    private static final int LOG_KEY_PATH_SEGMENTS = 4;
    private static final String SCHEMA_PATH = "/api/v1/guard/schema";

    private final ServiceAuthProperties properties;
    private final RateLimitedWarn rateLimitedWarn = new RateLimitedWarn();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.isActive() || isPublicPath(canonicalPath(request));
    }

    /**
     * 컨트롤러 매핑이 보는 것과 같은 경로: 퍼센트 디코딩, 세미콜론 매개변수 제거, 중복 슬래시와 {@code .} / {@code ..} 정리.
     * ({@code getRequestURI()} 는 디코딩 전 원본이라 필터와 매핑이 서로 다른 경로를 보게 된다.)
     */
    static String canonicalPath(HttpServletRequest request) {
        String decoded = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        return StringUtils.cleanPath(decoded);
    }

    static boolean isPublicPath(String path) {
        for (String prefix : PUBLIC_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/") || (prefix.equals("/swagger-ui") && path.equals("/swagger-ui.html"))) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Optional<String> caller = properties.authenticate(request.getHeader(ServiceAuthProperties.HEADER_NAME));
        if (caller.isPresent()) {
            request.setAttribute(ServiceAuthProperties.CALLER_ATTRIBUTE, caller.get());
            if (properties.isSharedTokenDeprecated(caller.get())) {
                rateLimitedWarn.warn(log, "rest-shared:" + boundedPath(request.getRequestURI()),
                        ServiceAuthProperties.SHARED_TOKEN_WARNING + ": transport=rest, path={}", request.getRequestURI());
            }
            if (isSchemaWrite(request) && !properties.hasScope(caller.get(), ServiceAuthProperties.SCOPE_SCHEMA_WRITE)) {
                log.warn("Guard schema write without the schema-write scope: caller={}, method={}, mode={}",
                        caller.get(), request.getMethod(), properties.getMode());
                if (properties.getMode() == ServiceAuthProperties.Mode.ENFORCE) {
                    writeError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "스키마를 변경할 권한이 없습니다.");
                    return;
                }
            }
        } else {
            rateLimitedWarn.warn(log, "rest:" + request.getMethod() + ":" + boundedPath(request.getRequestURI()),
                    "Guard REST call without a valid service token: method={}, path={}, mode={}",
                    request.getMethod(), request.getRequestURI(), properties.getMode());
            if (properties.getMode() == ServiceAuthProperties.Mode.ENFORCE) {
                writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "서비스 인증이 필요합니다.");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    /** 앞의 4개 세그먼트만 남긴다. 예: /api/v1/guard/tuples/abc/def -> /api/v1/guard/tuples */
    static String boundedPath(String uri) {
        if (uri == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int segments = 0;
        for (String segment : uri.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            if (segments++ >= LOG_KEY_PATH_SEGMENTS) {
                break;
            }
            sb.append('/').append(segment);
        }
        return sb.toString();
    }

    private static boolean isSchemaWrite(HttpServletRequest request) {
        String method = request.getMethod();
        boolean readOnly = "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
        String path = canonicalPath(request);
        return !readOnly && (path.equals(SCHEMA_PATH) || path.startsWith(SCHEMA_PATH + "/"));
    }

    private static void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"code\":\"" + code + "\","
                + "\"message\":\"" + message + "\",\"status\":" + status + "}");
    }
}

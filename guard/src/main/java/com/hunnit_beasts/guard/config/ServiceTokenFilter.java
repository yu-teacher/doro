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
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** /api/v1/guard/** REST 호출에 서비스 토큰 검증을 적용한다. (health, swagger 등은 대상이 아니다) */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class ServiceTokenFilter extends OncePerRequestFilter {

    private static final String PROTECTED_PREFIX = "/api/v1/guard";

    private final ServiceAuthProperties properties;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.isActive() || !request.getRequestURI().startsWith(PROTECTED_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        boolean authenticated = properties.matches(request.getHeader(ServiceAuthProperties.HEADER_NAME));
        if (!authenticated) {
            log.warn("Guard REST call without a valid service token: method={}, path={}, mode={}",
                    request.getMethod(), request.getRequestURI(), properties.getMode());
            if (properties.getMode() == ServiceAuthProperties.Mode.ENFORCE) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"success\":false,\"code\":\"UNAUTHORIZED\","
                        + "\"message\":\"서비스 인증이 필요합니다.\",\"status\":401}");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}

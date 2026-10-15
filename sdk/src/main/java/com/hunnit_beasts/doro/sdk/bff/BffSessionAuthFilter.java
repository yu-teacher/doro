package com.hunnit_beasts.doro.sdk.bff;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunnit_beasts.doro.sdk.domain.DoroUser;
import com.hunnit_beasts.doro.sdk.domain.DoroUserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/**
 * 세션 쿠키를 {@link DoroUser} 로 바꿔 {@link DoroUserContext} 에 넣는다. 이후 {@code @CurrentDoroUser}, {@code @DoroGuard}
 * 는 Bearer 토큰이나 API 키로 들어온 요청과 똑같이 동작한다.
 *
 * <p>쿠키는 브라우저가 자동으로 붙이므로 CSRF 방어가 필요하다. 쿠키가 있는 요청이 상태를 바꾸는 메서드(POST/PUT/PATCH/DELETE)면
 * (1) 커스텀 헤더가 있어야 하고(다른 사이트는 CORS 허용 없이 커스텀 헤더를 붙일 수 없다) (2) Origin 헤더가 있다면 이 사이트와 같아야 한다.
 */
public class BffSessionAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BffSessionAuthFilter.class);
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final BffAuthService authService;
    private final BffProperties props;
    private final BffCookies cookies;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BffSessionAuthFilter(BffAuthService authService, BffProperties props, BffCookies cookies) {
        this.authService = authService;
        this.props = props;
        this.cookies = cookies;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String cookieValue = cookies.sessionValue(request);
        if (cookieValue == null) {
            chain.doFilter(request, response);
            return;
        }

        if (!SAFE_METHODS.contains(request.getMethod()) && !passesCsrfCheck(request)) {
            log.warn("Rejected a cookie-authenticated request without a valid CSRF proof: method={}, uri={}",
                    request.getMethod(), request.getRequestURI());
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(BffResponses.error(HttpStatus.FORBIDDEN, "AUTH-403-03",
                    "요청을 확인할 수 없습니다. 페이지를 새로고침한 뒤 다시 시도해 주세요.")));
            return;
        }

        // Bearer 토큰이나 다른 방식으로 이미 인증된 요청이면 그대로 둔다
        DoroUser existing = DoroUserContext.getCurrentUser();
        if (existing != null && existing.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }

        Optional<DoroUser> user = authService.authenticate(cookieValue);
        if (user.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        DoroUserContext.setCurrentUser(user.get());
        try {
            chain.doFilter(request, response);
        } finally {
            DoroUserContext.clear();
        }
    }

    private boolean passesCsrfCheck(HttpServletRequest request) {
        String header = request.getHeader(props.getCsrfHeader());
        if (header == null || header.isBlank()) {
            return false;
        }
        String origin = request.getHeader("Origin");
        return origin == null || origin.equals(props.publicOrigin());
    }
}

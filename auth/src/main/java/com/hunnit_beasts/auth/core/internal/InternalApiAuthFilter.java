package com.hunnit_beasts.auth.core.internal;

import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.common.web.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

/**
 * 서브 서비스 전용 내부 API(/internal/**)를 지킨다.
 *
 * <ol>
 *   <li><b>게이트웨이를 거친 요청은 없는 경로처럼 404.</b> 내부 API 는 게이트웨이가 라우팅하지 않으므로 {@code X-Forwarded-For}/{@code X-Real-IP} 가
 *       붙은 요청은 외부에서 온 것이다. 존재 여부도 알려 주지 않는다.</li>
 *   <li><b>호출자 서비스 토큰.</b> 헤더 {@value #TOKEN_HEADER} 의 토큰이 설정된 {@code 이름:토큰} 중 하나와 같아야 한다.
 *       모드 OFF/WARN/ENFORCE: ENFORCE(기본)는 실패하면 401, WARN 은 호출자 이름 없이 경고만 남기고 통과(도입 중 확인용), OFF 는 검사하지 않는다.</li>
 * </ol>
 * 네트워크 격리에만 의존하던 이전 방식에 호출자 인증을 더한 심층 방어다. 토큰 값은 로그에 남기지 않는다.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class InternalApiAuthFilter extends OncePerRequestFilter {

    public static final String TOKEN_HEADER = "X-Doro-Service-Token";
    private static final String INTERNAL_PREFIX = "/internal/";

    private final InternalAuthMode mode;
    private final InternalCallerTokens tokens;

    public InternalApiAuthFilter(
            @Value("${doro.iam.internal-auth.mode:ENFORCE}") String mode,
            @Value("${doro.iam.internal-auth.tokens:}") String tokens) {
        this.mode = InternalAuthMode.parse(mode);
        this.tokens = InternalCallerTokens.parse(tokens);
        if (this.mode == InternalAuthMode.ENFORCE && this.tokens.isEmpty()) {
            log.warn("Internal API auth is ENFORCE but no caller tokens are configured (DORO_IAM_INTERNAL_TOKENS): every /internal request will be rejected");
        } else if (this.mode != InternalAuthMode.ENFORCE) {
            log.warn("Internal API auth mode is {} (callers={}): set DORO_IAM_INTERNAL_AUTH_MODE=ENFORCE once every caller sends its token", this.mode, this.tokens.size());
        } else {
            log.info("Internal API auth enforced for {} caller(s)", this.tokens.size());
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = RequestPaths.canonical(request);
        return !(uri.equals("/internal") || uri.startsWith(INTERNAL_PREFIX));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getHeader("X-Forwarded-For") != null || request.getHeader("X-Real-IP") != null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (mode == InternalAuthMode.OFF) {
            chain.doFilter(request, response);
            return;
        }
        Optional<String> caller = tokens.callerOf(request.getHeader(TOKEN_HEADER));
        if (caller.isPresent()) {
            log.debug("Internal API call: caller={}, path={}", caller.get(), RequestPaths.canonical(request));
            chain.doFilter(request, response);
            return;
        }
        boolean hadToken = request.getHeader(TOKEN_HEADER) != null;
        if (mode == InternalAuthMode.WARN) {
            log.warn("Internal API call without a valid caller token (allowed in WARN mode): path={}, tokenPresented={}", RequestPaths.canonical(request), hadToken);
            chain.doFilter(request, response);
            return;
        }
        log.warn("Internal API call rejected: path={}, tokenPresented={}", RequestPaths.canonical(request), hadToken);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        ErrorCode code = ErrorCode.UNAUTHORIZED;
        // 표준 응답 봉투와 같은 모양(ApiResponse.error). 필터에서는 JSON 변환기에 기대지 않고 고정 문구를 직접 쓴다.
        response.getWriter().write("{\"success\":false,\"error\":{\"code\":\"" + jsonEscape(code.getCode()) + "\",\"message\":\""
                + jsonEscape(code.getMessage()) + "\"},\"timestamp\":\"" + Instant.now() + "\"}");
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

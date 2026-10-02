package com.hunnit_beasts.auth.core.token;

import com.hunnit_beasts.auth.core.redis.KillSwitchPublisher;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** OAuth 클라이언트용 액세스 토큰(cid)이 IAM 에서 인증으로 인정되는 유일한 경로: UserInfo 와 SDK 의 세션 확인 */
    private static final Set<String> OAUTH_TOKEN_ALLOWED_PATHS = Set.of("/oauth2/userinfo", "/api/v1/sessions/current");

    private final JwtTokenProvider jwtTokenProvider;
    private final KillSwitchPublisher killSwitchPublisher;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);

        if (StringUtils.hasText(token)) {
            try {
                Claims claims = jwtTokenProvider.parseAndValidateToken(token);
                if (!acceptableAtIam(claims, request)) {
                    filterChain.doFilter(request, response);
                    return;
                }
                UUID userId = jwtTokenProvider.getUserId(claims);
                UUID sessionId = jwtTokenProvider.getSessionId(claims);

                if (!killSwitchPublisher.isSessionBlacklisted(sessionId)) {
                    String role = claims.get("role", String.class);
                    List<GrantedAuthority> authorities = new ArrayList<>();
                    authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
                    if ("ADMIN".equalsIgnoreCase(role) || "SUPER_ADMIN".equalsIgnoreCase(role)) {
                        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                    }
                    if ("SUPER_ADMIN".equalsIgnoreCase(role)) {
                        authorities.add(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
                    }

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    userId,
                                    null,
                                    authorities
                            );
                    authentication.setDetails(claims);
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    org.slf4j.MDC.put("userId", userId.toString());
                } else {
                    log.warn("Rejected request with blacklisted session: {}", sessionId);
                }
            } catch (Exception e) {
                log.warn("JWT authentication failed: {}", e.getMessage(), e);
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 이 토큰이 IAM 의 이 요청을 인증할 수 있는지. id_token(aud 있음)은 클라이언트가 읽는 용도라 어디서도 안 되고,
     * OAuth 클라이언트용 액세스 토큰(cid 있음)은 userinfo / 세션 확인에서만 된다. 둘 다 아니면(일반 로그인 토큰) 항상 된다.
     */
    private boolean acceptableAtIam(Claims claims, HttpServletRequest request) {
        Set<String> audience = claims.getAudience();
        if (audience != null && !audience.isEmpty()) {
            log.warn("Rejected an ID token presented as an access token: path={}", request.getRequestURI());
            return false;
        }
        if (claims.get(JwtTokenProvider.CLAIM_CLIENT_ID) != null
                && !OAUTH_TOKEN_ALLOWED_PATHS.contains(request.getRequestURI())) {
            log.warn("Rejected an OAuth client token on an IAM API: path={}", request.getRequestURI());
            return false;
        }
        return true;
    }

    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}

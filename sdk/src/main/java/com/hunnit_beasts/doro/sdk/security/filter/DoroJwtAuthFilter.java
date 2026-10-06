package com.hunnit_beasts.doro.sdk.security.filter;

import com.hunnit_beasts.doro.sdk.config.DoroProperties.IssuerValidation;
import com.hunnit_beasts.doro.sdk.config.DoroProperties.RevocationCheck;
import com.hunnit_beasts.doro.sdk.domain.DoroUser;
import com.hunnit_beasts.doro.sdk.domain.DoroUserContext;
import com.hunnit_beasts.doro.sdk.security.DoroTokenVerifier;
import com.hunnit_beasts.doro.sdk.security.jwks.JwksKeyProvider;
import com.hunnit_beasts.doro.sdk.security.revocation.SessionRevocationChecker;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
public class DoroJwtAuthFilter extends OncePerRequestFilter {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String MDC_TRACE_ID = "traceId";
    private static final String MDC_USER_ID = "userId";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private final String cookieName;
    private final DoroTokenVerifier verifier;

    public DoroJwtAuthFilter(JwksKeyProvider jwksKeyProvider) {
        this(jwksKeyProvider, null, IssuerValidation.OFF, "", 0);
    }

    public DoroJwtAuthFilter(JwksKeyProvider jwksKeyProvider,
                             String expectedIssuer,
                             IssuerValidation issuerValidation,
                             String cookieName,
                             long clockSkewSeconds) {
        this(jwksKeyProvider, expectedIssuer, issuerValidation, cookieName, clockSkewSeconds, "");
    }

    /** requiredAudience 가 비어 있지 않으면 aud 클레임에 해당 값이 없는 토큰을 거부한다. */
    public DoroJwtAuthFilter(JwksKeyProvider jwksKeyProvider,
                             String expectedIssuer,
                             IssuerValidation issuerValidation,
                             String cookieName,
                             long clockSkewSeconds,
                             String requiredAudience) {
        this(jwksKeyProvider, expectedIssuer, issuerValidation, cookieName, clockSkewSeconds, requiredAudience,
                RevocationCheck.OFF, null, true);
    }

    /**
     * 세션 폐기 확인을 포함하는 생성자. revocationMode 가 OFF 이거나 revocationChecker 가 null 이면
     * 폐기 확인을 하지 않는다(추가 네트워크 호출 없음).
     */
    public DoroJwtAuthFilter(JwksKeyProvider jwksKeyProvider,
                             String expectedIssuer,
                             IssuerValidation issuerValidation,
                             String cookieName,
                             long clockSkewSeconds,
                             String requiredAudience,
                             RevocationCheck revocationMode,
                             SessionRevocationChecker revocationChecker,
                             boolean revocationFailOpen) {
        this(jwksKeyProvider, expectedIssuer, issuerValidation, cookieName, clockSkewSeconds, requiredAudience,
                revocationMode, revocationChecker, revocationFailOpen, Set.of());
    }

    /**
     * acceptedOAuthClientIds: 받아 주는 OAuth 클라이언트 ID. 비어 있으면 cid 클레임이 있는 토큰(제3자 클라이언트가
     * 사용자 대신 받은 토큰)을 모두 거부한다.
     */
    public DoroJwtAuthFilter(JwksKeyProvider jwksKeyProvider,
                             String expectedIssuer,
                             IssuerValidation issuerValidation,
                             String cookieName,
                             long clockSkewSeconds,
                             String requiredAudience,
                             RevocationCheck revocationMode,
                             SessionRevocationChecker revocationChecker,
                             boolean revocationFailOpen,
                             Set<String> acceptedOAuthClientIds) {
        this(new DoroTokenVerifier(jwksKeyProvider, expectedIssuer, issuerValidation, clockSkewSeconds, requiredAudience,
                revocationMode, revocationChecker, revocationFailOpen, acceptedOAuthClientIds), cookieName);
    }

    /** 이미 만든 검증기를 공유하는 생성자 (BFF 등 다른 경로도 같은 검증 규칙을 쓰도록). */
    public DoroJwtAuthFilter(DoroTokenVerifier verifier, String cookieName) {
        this.verifier = verifier;
        this.cookieName = cookieName != null ? cookieName.trim() : "";
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId == null || !SAFE_TRACE_ID.matcher(traceId).matches()) {
            traceId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_TRACE_ID, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);

        String token = resolveToken(request);
        if (token != null) {
            try {
                DoroUser user = verifier.verify(token);
                if (user != null) {
                    DoroUserContext.setCurrentUser(user);
                    if (user.userId() != null) {
                        MDC.put(MDC_USER_ID, user.userId().toString());
                    }
                    log.debug("Authenticated Doro user: userId={}, sid={}", user.userId(), user.sessionId());
                }
            } catch (Exception e) {
                log.warn("Failed to authenticate Doro JWT: {}", e.getMessage());
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            DoroUserContext.clear();
            MDC.remove(MDC_TRACE_ID);
            MDC.remove(MDC_USER_ID);
        }
    }

    /** Authorization: Bearer 헤더를 우선하고, 없을 때만 설정된 쿠키에서 토큰을 읽는다. */
    private String resolveToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length()).trim();
        }
        if (!cookieName.isEmpty() && request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (cookieName.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                    return cookie.getValue().trim();
                }
            }
        }
        return null;
    }
}

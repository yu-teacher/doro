package com.hunnit_beasts.doro.sdk.security.filter;

import com.hunnit_beasts.doro.sdk.config.DoroProperties.IssuerValidation;
import com.hunnit_beasts.doro.sdk.domain.DoroUser;
import com.hunnit_beasts.doro.sdk.domain.DoroUserContext;
import com.hunnit_beasts.doro.sdk.security.jwks.JwksKeyProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
public class DoroJwtAuthFilter extends OncePerRequestFilter {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String MDC_TRACE_ID = "traceId";
    private static final String MDC_USER_ID = "userId";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String REQUIRED_ALGORITHM = "RS256";
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private final String expectedIssuer;
    private final IssuerValidation issuerValidation;
    private final String cookieName;
    private final JwtParser parser;

    public DoroJwtAuthFilter(JwksKeyProvider jwksKeyProvider) {
        this(jwksKeyProvider, null, IssuerValidation.OFF, "", 0);
    }

    public DoroJwtAuthFilter(JwksKeyProvider jwksKeyProvider,
                             String expectedIssuer,
                             IssuerValidation issuerValidation,
                             String cookieName,
                             long clockSkewSeconds) {
        this.expectedIssuer = expectedIssuer;
        this.issuerValidation = issuerValidation != null ? issuerValidation : IssuerValidation.OFF;
        this.cookieName = cookieName != null ? cookieName.trim() : "";
        this.parser = Jwts.parser()
                .clockSkewSeconds(clockSkewSeconds)
                .keyLocator(header -> {
                    String kid = (String) header.get("kid");
                    return kid != null ? jwksKeyProvider.getPublicKey(kid) : null;
                })
                .build();
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
                DoroUser user = validateAndExtractUser(token);
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

    private DoroUser validateAndExtractUser(String token) {
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            return null;
        }

        Jws<Claims> claimsJws = parser.parseSignedClaims(token);

        if (!REQUIRED_ALGORITHM.equals(claimsJws.getHeader().getAlgorithm())) {
            throw new IllegalStateException("Unsupported JWT algorithm");
        }

        Claims claims = claimsJws.getPayload();
        if (claims.getExpiration() == null) {
            throw new IllegalStateException("JWT without exp claim is rejected");
        }
        verifyIssuer(claims);

        String sub = claims.getSubject();
        String email = claims.get("email", String.class);
        String sid = claims.get("sid", String.class);
        Integer uidx = claims.get("uidx", Integer.class);

        UUID userId = (sub != null) ? UUID.fromString(sub) : null;
        UUID sessionId = (sid != null) ? UUID.fromString(sid) : null;
        int userIndex = (uidx != null) ? uidx : 0;
        String role = claims.get("role", String.class);

        return new DoroUser(userId, email, sessionId, userIndex, role != null ? role : "USER");
    }

    private void verifyIssuer(Claims claims) {
        if (issuerValidation == IssuerValidation.OFF || expectedIssuer == null || expectedIssuer.isBlank()) {
            return;
        }
        if (expectedIssuer.equals(claims.getIssuer())) {
            return;
        }
        if (issuerValidation == IssuerValidation.ENFORCE) {
            throw new IllegalStateException("JWT issuer mismatch");
        }
        log.warn("JWT issuer mismatch (mode=WARN): expected={}, actual={}", expectedIssuer, claims.getIssuer());
    }
}

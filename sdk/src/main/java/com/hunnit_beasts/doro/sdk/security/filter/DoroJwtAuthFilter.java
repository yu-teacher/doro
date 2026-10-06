package com.hunnit_beasts.doro.sdk.security.filter;

import com.hunnit_beasts.doro.sdk.config.DoroProperties.IssuerValidation;
import com.hunnit_beasts.doro.sdk.config.DoroProperties.RevocationCheck;
import com.hunnit_beasts.doro.sdk.domain.DoroUser;
import com.hunnit_beasts.doro.sdk.domain.DoroUserContext;
import com.hunnit_beasts.doro.sdk.security.jwks.JwksKeyProvider;
import com.hunnit_beasts.doro.sdk.security.revocation.SessionRevocationChecker;
import com.hunnit_beasts.doro.sdk.security.revocation.SessionRevocationChecker.Verdict;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final String requiredAudience;
    private final JwtParser parser;
    private final RevocationCheck revocationMode;
    private final SessionRevocationChecker revocationChecker;
    private final boolean revocationFailOpen;
    private final Set<String> acceptedOAuthClientIds;
    private final AtomicBoolean sidlessWarned = new AtomicBoolean(false);

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
        this.acceptedOAuthClientIds = acceptedOAuthClientIds != null ? Set.copyOf(acceptedOAuthClientIds) : Set.of();
        this.revocationMode = revocationMode != null ? revocationMode : RevocationCheck.OFF;
        this.revocationChecker = revocationChecker;
        this.revocationFailOpen = revocationFailOpen;
        this.requiredAudience = requiredAudience != null ? requiredAudience.trim() : "";
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
        verifyAudience(claims);
        String clientId = verifyOAuthClient(claims);

        String sub = claims.getSubject();
        String email = claims.get("email", String.class);
        String sid = claims.get("sid", String.class);
        Integer uidx = claims.get("uidx", Integer.class);

        UUID userId = (sub != null) ? UUID.fromString(sub) : null;
        UUID sessionId = (sid != null) ? UUID.fromString(sid) : null;
        int userIndex = (uidx != null) ? uidx : 0;
        String role = claims.get("role", String.class);

        if (!passesRevocationCheck(sessionId, token, claims)) {
            return null;
        }

        return new DoroUser(userId, email, sessionId, userIndex, role != null ? role : "USER", clientId);
    }

    /** JWT 검증이 끝난 뒤 IAM 에 세션 유효성을 확인한다. false 이면 요청을 익명으로 처리한다. */
    private boolean passesRevocationCheck(UUID sessionId, String token, Claims claims) {
        if (revocationMode == RevocationCheck.OFF || revocationChecker == null) {
            return true;
        }
        if (sessionId == null) {
            if (sidlessWarned.compareAndSet(false, true)) {
                log.warn("Session revocation check skipped: token has no sid claim (logged once)");
            }
            return true;
        }
        Verdict verdict = revocationChecker.check(sessionId, token, claims.getExpiration().getTime());
        boolean enforce = revocationMode == RevocationCheck.ENFORCE;
        switch (verdict) {
            case ACTIVE:
                return true;
            case REVOKED:
                if (enforce) {
                    log.warn("Rejected request with revoked session: sid={}", sessionId);
                    return false;
                }
                log.warn("Session is revoked (mode=WARN, request allowed): sid={}", sessionId);
                return true;
            default:
                if (enforce && !revocationFailOpen) {
                    log.warn("Rejected request: session state unavailable and fail-open=false: sid={}", sessionId);
                    return false;
                }
                return true;
        }
    }

    /**
     * OAuth 클라이언트가 받은 토큰(cid)은 허용 목록에 있는 클라이언트의 것만 통과시킨다. 일반 로그인 토큰(cid 없음)은 영향이 없다.
     * 허용하지 않으면 제3자 앱의 토큰이 이 서비스의 모든 보호 API 에서 사용자 본인의 전권으로 동작하게 된다.
     *
     * @return 토큰의 클라이언트 ID, 일반 토큰이면 null
     */
    private String verifyOAuthClient(Claims claims) {
        String clientId = claims.get("cid", String.class);
        if (clientId == null || clientId.isBlank()) {
            return null;
        }
        if (!acceptedOAuthClientIds.contains(clientId)) {
            throw new IllegalStateException("OAuth client token is not accepted (doro.iam.oauth-client-ids does not include it)");
        }
        return clientId;
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

    private void verifyAudience(Claims claims) {
        Set<String> audience = claims.getAudience();
        if (requiredAudience.isEmpty()) {
            // audience 를 설정하지 않은 서비스는 aud 가 없는 일반 액세스 토큰만 받는다. aud 가 있는 토큰은 특정 클라이언트용
            // (OIDC id_token 등)이라 API 인증으로 쓰이면 토큰 혼동이 된다.
            if (audience != null && !audience.isEmpty()) {
                throw new IllegalStateException("JWT with an audience is not an access token (doro.iam.audience is not configured)");
            }
            return;
        }
        if (audience == null || !audience.contains(requiredAudience)) {
            throw new IllegalStateException("JWT audience mismatch");
        }
    }
}

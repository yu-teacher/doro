package com.hunnit_beasts.doro.sdk.security;

import com.hunnit_beasts.doro.sdk.config.DoroProperties.IssuerValidation;
import com.hunnit_beasts.doro.sdk.config.DoroProperties.RevocationCheck;
import com.hunnit_beasts.doro.sdk.domain.DoroUser;
import com.hunnit_beasts.doro.sdk.security.jwks.JwksKeyProvider;
import com.hunnit_beasts.doro.sdk.security.revocation.SessionRevocationChecker;
import com.hunnit_beasts.doro.sdk.security.revocation.SessionRevocationChecker.Verdict;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Doro 액세스 토큰(JWT)을 검증해 {@link DoroUser} 로 바꾼다. 서블릿 필터와 BFF 처럼 토큰을 다른 경로로 받는 서비스가
 * 같은 규칙(서명, RS256 고정, exp 필수, iss/aud/cid 검증, 세션 폐기 확인)을 쓰도록 필터에서 분리한 클래스다.
 */
@Slf4j
public class DoroTokenVerifier {

    private static final String REQUIRED_ALGORITHM = "RS256";

    private final String expectedIssuer;
    private final IssuerValidation issuerValidation;
    private final String requiredAudience;
    private final JwtParser parser;
    private final RevocationCheck revocationMode;
    private final SessionRevocationChecker revocationChecker;
    private final boolean revocationFailOpen;
    private final Set<String> acceptedOAuthClientIds;
    private final AtomicBoolean sidlessWarned = new AtomicBoolean(false);

    public DoroTokenVerifier(JwksKeyProvider jwksKeyProvider,
                             String expectedIssuer,
                             IssuerValidation issuerValidation,
                             long clockSkewSeconds,
                             String requiredAudience,
                             RevocationCheck revocationMode,
                             SessionRevocationChecker revocationChecker,
                             boolean revocationFailOpen,
                             Set<String> acceptedOAuthClientIds) {
        this.revocationMode = revocationMode != null ? revocationMode : RevocationCheck.OFF;
        this.revocationChecker = revocationChecker;
        this.revocationFailOpen = revocationFailOpen;
        this.requiredAudience = requiredAudience != null ? requiredAudience.trim() : "";
        this.expectedIssuer = expectedIssuer;
        this.issuerValidation = issuerValidation != null ? issuerValidation : IssuerValidation.OFF;
        this.acceptedOAuthClientIds = acceptedOAuthClientIds != null ? Set.copyOf(acceptedOAuthClientIds) : Set.of();
        this.parser = Jwts.parser()
                .clockSkewSeconds(clockSkewSeconds)
                .keyLocator(header -> {
                    String kid = (String) header.get("kid");
                    return kid != null ? jwksKeyProvider.getPublicKey(kid) : null;
                })
                .build();
    }

    /**
     * 토큰을 검증하고 사용자로 변환한다.
     *
     * @return 검증을 통과한 사용자. 형식이 JWT 가 아니거나 세션이 폐기됐으면 null
     * @throws RuntimeException 서명·만료·발급자·audience·클라이언트 검증에 실패하면
     */
    public DoroUser verify(String token) {
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

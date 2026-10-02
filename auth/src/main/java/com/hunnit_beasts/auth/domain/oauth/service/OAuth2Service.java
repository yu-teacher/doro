package com.hunnit_beasts.auth.domain.oauth.service;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.common.log.RateLimitedLogGate;
import com.hunnit_beasts.auth.core.token.JwtTokenProvider;
import com.hunnit_beasts.auth.core.token.RefreshTokenService;
import com.hunnit_beasts.auth.domain.auth.dto.TokenResponse;
import com.hunnit_beasts.auth.domain.oauth.dto.OAuth2TokenRequest;
import com.hunnit_beasts.auth.domain.oauth.service.OAuthClientRegistry.ResolvedClient;
import com.hunnit_beasts.auth.domain.oauth.store.AuthorizationCodeData;
import com.hunnit_beasts.auth.domain.oauth.store.AuthorizationCodeStore;
import com.hunnit_beasts.auth.domain.session.entity.UserSession;
import com.hunnit_beasts.auth.domain.session.service.SessionService;
import com.hunnit_beasts.auth.domain.user.entity.User;
import com.hunnit_beasts.auth.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OAuth2Service {

    /** 서버가 코드를 만들 때 쓰는 난수 바이트 수 (base64url 43자) */
    private static final int AUTHORIZATION_CODE_BYTES = 32;
    /** picture 클레임으로 내보낼 수 있는 URL 의 최대 길이 */
    private static final int MAX_PICTURE_URL_LENGTH = 2048;

    private final UserRepository userRepository;
    private final SessionService sessionService;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final AuthorizationCodeStore codeStore;
    private final OAuthClientRegistry clientRegistry;
    private final RateLimitedLogGate logGate;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${doro.iam.issuer:https://auth.doro.local}")
    private String issuer;

    @Value("${doro.iam.jwt.access-token-validity-seconds:900}")
    private long accessTokenValiditySeconds;

    /** 만료된 인가 코드를 주기적으로 정리한다. 교환되지 않고 방치된 코드가 메모리에 쌓이는 것을 막는다(메모리 저장소 전용). */
    @Scheduled(fixedDelayString = "${doro.oauth.cleanup-interval-ms:60000}",
            initialDelayString = "${doro.oauth.cleanup-initial-delay-ms:60000}")
    public void purgeExpiredAuthorizationCodes() {
        purgeExpiredAuthorizationCodes(Instant.now());
    }

    /** @return 제거한 코드 수 */
    public int purgeExpiredAuthorizationCodes(Instant now) {
        return codeStore.purgeExpired(now);
    }

    public int pendingAuthorizationCodeCount() {
        return codeStore.pendingCount();
    }

    // ------------------------------------------------------------------ 인가 단계

    /** 1단계: client_id 와 redirect_uri 검증. 실패하면 호출자는 절대 redirect_uri 로 리다이렉트하지 않는다. */
    public ResolvedClient validateClientAndRedirect(String clientId, String redirectUri) {
        return clientRegistry.resolveForAuthorization(clientId, redirectUri);
    }

    /**
     * 2단계: 나머지 인가 파라미터 검증.
     *
     * @return 정규화한 승인 스코프(공백 구분, 없으면 빈 문자열)
     */
    public String validateAuthorizationParameters(ResolvedClient client, String responseType, String codeChallenge,
                                                  String codeChallengeMethod, String scope, String state, String nonce) {
        if (responseType == null || responseType.isBlank()) {
            throw new OAuth2Exception(OAuth2ErrorType.INVALID_REQUEST, "response_type 은 필수입니다.");
        }
        if (!OAuth2Constants.RESPONSE_TYPE_CODE.equals(responseType)) {
            throw new OAuth2Exception(OAuth2ErrorType.UNSUPPORTED_RESPONSE_TYPE, "response_type 은 code 만 지원합니다.");
        }
        if (codeChallenge == null || codeChallenge.isBlank()) {
            throw new OAuth2Exception(OAuth2ErrorType.INVALID_REQUEST, "code_challenge 는 필수입니다 (PKCE).");
        }
        if (!OAuth2Constants.CODE_CHALLENGE_PATTERN.matcher(codeChallenge).matches()) {
            throw new OAuth2Exception(OAuth2ErrorType.INVALID_REQUEST, "code_challenge 형식이 올바르지 않습니다.");
        }
        if (codeChallengeMethod != null && !OAuth2Constants.PKCE_METHOD_S256.equals(codeChallengeMethod)) {
            throw new OAuth2Exception(OAuth2ErrorType.INVALID_REQUEST, "code_challenge_method 는 S256 만 지원합니다.");
        }
        if (state != null && state.length() > OAuth2Constants.MAX_STATE_LENGTH) {
            throw new OAuth2Exception(OAuth2ErrorType.INVALID_REQUEST, "state 가 너무 깁니다.");
        }
        if (nonce != null && nonce.length() > OAuth2Constants.MAX_NONCE_LENGTH) {
            throw new OAuth2Exception(OAuth2ErrorType.INVALID_REQUEST, "nonce 가 너무 깁니다.");
        }
        return String.join(" ", clientRegistry.resolveScopes(client, scope));
    }

    /** 3단계: 인가 코드 발급(사용자가 로그인·동의한 뒤). 파라미터는 이미 검증된 값이어야 한다. */
    public String issueAuthorizationCode(ResolvedClient client, String redirectUri, UUID userId, String codeChallenge,
                                         String scope, String nonce, long authTimeEpochSeconds) {
        byte[] bytes = new byte[AUTHORIZATION_CODE_BYTES];
        secureRandom.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        codeStore.save(code, new AuthorizationCodeData(userId, client.clientId(), redirectUri, codeChallenge,
                scope == null ? "" : scope, nonce == null || nonce.isEmpty() ? null : nonce, authTimeEpochSeconds));
        return code;
    }

    /** 스코프·nonce 없이 코드를 발급하는 단순 경로(기존 호출부 호환). */
    public String generateAuthorizationCode(String clientId, String redirectUri, UUID userId, String codeChallenge) {
        ResolvedClient client = clientRegistry.resolveForAuthorization(clientId, redirectUri);
        return issueAuthorizationCode(client, redirectUri, userId, codeChallenge, "", null, Instant.now().getEpochSecond());
    }

    /** 액세스 토큰이 가리키는 세션이 아직 활성인지 확인한다(회수된 세션의 토큰으로 인가/UserInfo 를 받지 못하게). */
    public UserSession requireActiveSession(UUID sessionId) {
        try {
            return sessionService.getActiveSession(sessionId);
        } catch (AuthException e) {
            throw new AuthException(ErrorCode.UNAUTHORIZED, "세션이 유효하지 않습니다. 다시 로그인해 주세요.");
        }
    }

    // ------------------------------------------------------------------ 토큰 단계

    /** grant_type 에 따라 토큰 요청을 처리한다. */
    public TokenResponse token(OAuth2TokenRequest request) {
        String grantType = request.grantType();
        if (OAuth2Constants.GRANT_AUTHORIZATION_CODE.equals(grantType)) {
            return exchangeCode(request);
        }
        if (OAuth2Constants.GRANT_REFRESH_TOKEN.equals(grantType)) {
            return refresh(request);
        }
        throw new OAuth2Exception(OAuth2ErrorType.UNSUPPORTED_GRANT_TYPE,
                "grant_type 은 authorization_code 와 refresh_token 만 지원합니다.");
    }

    public TokenResponse exchangeCode(OAuth2TokenRequest request) {
        if (!OAuth2Constants.GRANT_AUTHORIZATION_CODE.equals(request.grantType())) {
            throw new OAuth2Exception(OAuth2ErrorType.UNSUPPORTED_GRANT_TYPE, "grant_type 이 authorization_code 가 아닙니다.");
        }
        requireText(request.code(), "code");
        requireText(request.redirectUri(), "redirect_uri");
        requireText(request.clientId(), "client_id");
        requireText(request.codeVerifier(), "code_verifier");
        ResolvedClient client = clientRegistry.resolveForToken(request.clientId());

        // 어떤 검증 실패든 코드는 이미 소비된 상태여야 한다(1회용). 소비가 먼저다.
        Optional<AuthorizationCodeData> consumed = codeStore.consume(request.code());
        if (consumed.isEmpty()) {
            if (codeStore.wasConsumed(request.code()) && logGate.tryAcquire("oauth-code-reuse")) {
                log.warn("SECURITY: an already-used authorization code was presented again: clientId={}", client.clientId());
            }
            throw new OAuth2Exception(OAuth2ErrorType.INVALID_GRANT, "인가 코드가 유효하지 않거나 만료되었습니다.", ErrorCode.INVALID_TOKEN);
        }
        AuthorizationCodeData data = consumed.get();

        if (!data.clientId().equals(request.clientId()) || !data.redirectUri().equals(request.redirectUri())) {
            log.warn("Authorization code exchange rejected: client_id or redirect_uri mismatch: clientId={}", client.clientId());
            throw new OAuth2Exception(OAuth2ErrorType.INVALID_GRANT, "client_id 또는 redirect_uri가 일치하지 않습니다.");
        }

        // PKCE S256 검증: BASE64URL-ENCODE(SHA256(code_verifier)) == code_challenge
        if (!OAuth2Constants.CODE_VERIFIER_PATTERN.matcher(request.codeVerifier()).matches()
                || !verifyPkceS256(request.codeVerifier(), data.codeChallenge())) {
            log.warn("PKCE verification failed for clientId={}", request.clientId());
            throw new OAuth2Exception(OAuth2ErrorType.INVALID_GRANT, "PKCE code_verifier 검증에 실패했습니다.", ErrorCode.INVALID_TOKEN);
        }

        User user = userRepository.findById(data.userId())
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
        if (!user.isActive()) {
            throw new AuthException(ErrorCode.ACCOUNT_SUSPENDED);
        }

        // 클라이언트별 마커로 세션을 만든다: 같은 클라이언트의 이전 OAuth 세션만 교체되고 다른 클라이언트 세션은 유지된다.
        UserSession session = sessionService.createSession(
                user.getId(),
                OAuth2Constants.SESSION_DEVICE_PREFIX + client.clientId(),
                OAuth2Constants.SESSION_IP_MARKER,
                OAuth2Constants.SESSION_USER_AGENT_PREFIX + client.clientId()
        );

        String accessToken = jwtTokenProvider.createAccessToken(
                user.getId(),
                user.getEmail(),
                session.getId(),
                session.getUserIndex(),
                user.getRole() != null ? user.getRole().name() : "USER"
        );
        String refreshToken = refreshTokenService.createRefreshToken(session.getId());

        Set<String> scopes = OAuthClientRegistry.parseScope(data.scope());
        String idToken = null;
        if (scopes.contains(OAuth2Constants.SCOPE_OPENID)) {
            idToken = jwtTokenProvider.createIdToken(user.getId(), client.clientId(), session.getId(), data.nonce(),
                    data.authTime(), idTokenUserClaims(user, scopes));
        }

        log.info("OAuth authorization code exchanged: clientId={}, userId={}, openid={}",
                client.clientId(), user.getId(), idToken != null);
        return TokenResponse.ofOAuth(accessToken, refreshToken, accessTokenValiditySeconds,
                session.getId(), session.getUserIndex(), idToken, data.scope());
    }

    /** refresh_token 그랜트: 기존 RTR 을 재사용하되 토큰이 해당 client_id 의 OAuth 세션에 속할 때만 허용한다. */
    public TokenResponse refresh(OAuth2TokenRequest request) {
        requireText(request.refreshToken(), "refresh_token");
        requireText(request.clientId(), "client_id");
        ResolvedClient client = clientRegistry.resolveForToken(request.clientId());
        if (request.scope() != null && !request.scope().isBlank()) {
            clientRegistry.resolveScopes(client, request.scope());
        }

        // 회전 전에 소유 클라이언트를 확인한다. 다른 클라이언트/일반 로그인의 토큰은 소모하지 않고 거부한다.
        // (이미 회전된 토큰이라도 OAuth 세션에 속하면 아래 rotate 에서 재사용 탐지가 동작한다.)
        UserSession owner = refreshTokenService.findSessionByRawToken(request.refreshToken()).orElse(null);
        if (owner == null || !isOAuthSessionOf(owner, client.clientId())) {
            log.warn("Refresh token grant rejected: token does not belong to an OAuth session of clientId={}", client.clientId());
            throw new OAuth2Exception(OAuth2ErrorType.INVALID_GRANT, "리프레시 토큰이 유효하지 않습니다.", ErrorCode.INVALID_TOKEN);
        }

        RefreshTokenService.RotatedTokenResult rotated;
        try {
            rotated = refreshTokenService.rotateRefreshToken(request.refreshToken());
        } catch (AuthException e) {
            throw new OAuth2Exception(OAuth2ErrorType.INVALID_GRANT, "리프레시 토큰이 유효하지 않습니다.", e.getErrorCode());
        }
        UserSession session = rotated.session();

        User user = userRepository.findById(session.getUserId())
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
        if (!user.isActive()) {
            sessionService.deactivateSession(session.getId());
            throw new AuthException(ErrorCode.ACCOUNT_SUSPENDED);
        }

        String accessToken = jwtTokenProvider.createAccessToken(
                user.getId(),
                user.getEmail(),
                session.getId(),
                session.getUserIndex(),
                user.getRole() != null ? user.getRole().name() : "USER"
        );
        log.info("OAuth refresh token rotated: clientId={}, userId={}", client.clientId(), user.getId());
        return TokenResponse.ofOAuth(accessToken, rotated.newRefreshToken(), accessTokenValiditySeconds,
                session.getId(), session.getUserIndex(), null, null);
    }

    private static boolean isOAuthSessionOf(UserSession session, String clientId) {
        return OAuth2Constants.SESSION_IP_MARKER.equals(session.getIpAddress())
                && (OAuth2Constants.SESSION_USER_AGENT_PREFIX + clientId).equals(session.getUserAgent());
    }

    // ------------------------------------------------------------------ OIDC

    /** id_token 에 담을 사용자 클레임(스코프 기준). picture 는 http(s) URL 일 때만 포함한다. */
    private Map<String, Object> idTokenUserClaims(User user, Set<String> scopes) {
        Map<String, Object> claims = new LinkedHashMap<>();
        if (scopes.contains(OAuth2Constants.SCOPE_EMAIL)) {
            claims.put("email", user.getEmail());
            claims.put("email_verified", false);
        }
        if (scopes.contains(OAuth2Constants.SCOPE_PROFILE)) {
            claims.put("name", user.getName());
            safePictureUrl(user.getProfileImageUrl()).ifPresent(url -> claims.put("picture", url));
        }
        return claims;
    }

    /** UserInfo 응답. 액세스 토큰에는 스코프가 없으므로 sub + email + name + (http(s)) picture 를 돌려준다. */
    public Map<String, Object> userInfo(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
        if (!user.isActive()) {
            throw new AuthException(ErrorCode.ACCOUNT_SUSPENDED);
        }
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", user.getId().toString());
        claims.put("email", user.getEmail());
        claims.put("email_verified", false);
        claims.put("name", user.getName());
        safePictureUrl(user.getProfileImageUrl()).ifPresent(url -> claims.put("picture", url));
        return claims;
    }

    /** http(s) 절대 URL 만 허용한다. data: URL 등은 토큰/응답에 싣지 않는다. */
    static Optional<String> safePictureUrl(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_PICTURE_URL_LENGTH) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            boolean web = "http".equals(scheme) || "https".equals(scheme);
            return web && uri.getHost() != null ? Optional.of(value.trim()) : Optional.empty();
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    public Map<String, Object> getOidcConfiguration() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("issuer", issuer);
        config.put("authorization_endpoint", issuer + "/oauth2/authorize");
        config.put("token_endpoint", issuer + "/oauth2/token");
        config.put("userinfo_endpoint", issuer + "/oauth2/userinfo");
        config.put("jwks_uri", issuer + "/.well-known/jwks.json");
        config.put("response_types_supported", List.of(OAuth2Constants.RESPONSE_TYPE_CODE));
        config.put("response_modes_supported", List.of("query"));
        config.put("grant_types_supported", List.of(OAuth2Constants.GRANT_AUTHORIZATION_CODE, OAuth2Constants.GRANT_REFRESH_TOKEN));
        config.put("subject_types_supported", List.of("public"));
        config.put("id_token_signing_alg_values_supported", List.of("RS256"));
        config.put("scopes_supported", OAuth2Constants.SUPPORTED_SCOPES);
        config.put("claims_supported", List.of("sub", "iss", "aud", "exp", "iat", "auth_time", "nonce", "sid",
                "email", "email_verified", "name", "picture"));
        config.put("code_challenge_methods_supported", List.of(OAuth2Constants.PKCE_METHOD_S256));
        config.put("token_endpoint_auth_methods_supported", List.of("none")); // Public client PKCE
        return config;
    }

    // ------------------------------------------------------------------ 유틸

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new OAuth2Exception(OAuth2ErrorType.INVALID_REQUEST, name + " 은(는) 필수입니다.");
        }
    }

    private boolean verifyPkceS256(String codeVerifier, String codeChallenge) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            String computedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            return MessageDigest.isEqual(
                    computedChallenge.getBytes(StandardCharsets.UTF_8),
                    codeChallenge.getBytes(StandardCharsets.UTF_8)
            );
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm missing", e);
        }
    }
}

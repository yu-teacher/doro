package com.hunnit_beasts.auth.domain.oauth.service;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.core.token.JwtTokenProvider;
import com.hunnit_beasts.auth.core.token.RefreshTokenService;
import com.hunnit_beasts.auth.domain.auth.dto.TokenResponse;
import com.hunnit_beasts.auth.domain.oauth.dto.OAuth2TokenRequest;
import com.hunnit_beasts.auth.domain.session.entity.UserSession;
import com.hunnit_beasts.auth.domain.session.service.SessionService;
import com.hunnit_beasts.auth.domain.user.entity.User;
import com.hunnit_beasts.auth.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class OAuth2Service {

    private final UserRepository userRepository;
    private final SessionService sessionService;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final SecureRandom secureRandom = new SecureRandom();

    private final Map<String, AuthCodeEntry> authCodeStore = new ConcurrentHashMap<>();

    @Value("${doro.iam.issuer:https://auth.doro.local}")
    private String issuer;

    @Value("${doro.iam.jwt.access-token-validity-seconds:900}")
    private long accessTokenValiditySeconds;

    public String generateAuthorizationCode(String clientId, String redirectUri, UUID userId, String codeChallenge) {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        Instant expiresAt = Instant.now().plus(5, ChronoUnit.MINUTES);
        authCodeStore.put(code, new AuthCodeEntry(code, clientId, redirectUri, userId, codeChallenge, expiresAt));

        return code;
    }

    public TokenResponse exchangeCode(OAuth2TokenRequest request) {
        if (!"authorization_code".equals(request.grantType())) {
            throw new AuthException(ErrorCode.INVALID_INPUT, "grant_type은 authorization_code만 지원합니다.");
        }

        AuthCodeEntry entry = authCodeStore.remove(request.code());
        if (entry == null || Instant.now().isAfter(entry.expiresAt())) {
            throw new AuthException(ErrorCode.INVALID_TOKEN, "인가 코드가 유효하지 않거나 만료되었습니다.");
        }

        if (!entry.clientId().equals(request.clientId()) || !entry.redirectUri().equals(request.redirectUri())) {
            throw new AuthException(ErrorCode.INVALID_INPUT, "client_id 또는 redirect_uri가 일치하지 않습니다.");
        }

        // PKCE S256 검증: BASE64URL-ENCODE(SHA256(code_verifier)) == code_challenge
        if (!verifyPkceS256(request.codeVerifier(), entry.codeChallenge())) {
            log.warn("PKCE verification failed for clientId={}", request.clientId());
            throw new AuthException(ErrorCode.INVALID_TOKEN, "PKCE code_verifier 검증에 실패했습니다.");
        }

        User user = userRepository.findById(entry.userId())
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));

        if (!user.isActive()) {
            throw new AuthException(ErrorCode.ACCOUNT_SUSPENDED);
        }

        UserSession session = sessionService.createSession(
                user.getId(),
                "OAuth2 Client: " + request.clientId(),
                "OAuth2",
                "OAuth2 PKCE Flow"
        );

        String accessToken = jwtTokenProvider.createAccessToken(
                user.getId(),
                user.getEmail(),
                session.getId(),
                session.getUserIndex(),
                user.getRole() != null ? user.getRole().name() : "USER"
        );
        String refreshToken = refreshTokenService.createRefreshToken(session.getId());

        return TokenResponse.of(
                accessToken,
                refreshToken,
                accessTokenValiditySeconds,
                session.getId(),
                session.getUserIndex()
        );
    }

    public Map<String, Object> getOidcConfiguration() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("issuer", issuer);
        config.put("authorization_endpoint", issuer + "/oauth2/authorize");
        config.put("token_endpoint", issuer + "/oauth2/token");
        config.put("jwks_uri", issuer + "/.well-known/jwks.json");
        config.put("response_types_supported", List.of("code"));
        config.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
        config.put("code_challenge_methods_supported", List.of("S256"));
        config.put("token_endpoint_auth_methods_supported", List.of("none")); // Public client PKCE
        return config;
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

    private record AuthCodeEntry(
            String code,
            String clientId,
            String redirectUri,
            UUID userId,
            String codeChallenge,
            Instant expiresAt
    ) {}
}

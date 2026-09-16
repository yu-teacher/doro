package com.hunnit_beasts.auth.core.token;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private final JwtKeyProvider keyProvider;

    @Value("${doro.iam.issuer:https://auth.doro.local}")
    private String issuer;

    @Value("${doro.iam.jwt.access-token-validity-seconds:900}")
    private long accessTokenValiditySeconds;

    /**
     * RS256 비대칭키로 서명된 Access Token 생성 (기본 USER)
     */
    public String createAccessToken(UUID userId, String email, UUID sessionId, int userIndex) {
        return createAccessToken(userId, email, sessionId, userIndex, "USER");
    }

    /**
     * RS256 비대칭키로 서명된 Access Token 생성 (Role 지정)
     */
    public String createAccessToken(UUID userId, String email, UUID sessionId, int userIndex, String role) {
        Date now = new Date();
        Date validity = new Date(now.getTime() + (accessTokenValiditySeconds * 1000));

        return Jwts.builder()
                .header()
                .keyId(keyProvider.getKeyId())
                .type("JWT")
                .and()
                .issuer(issuer)
                .subject(userId.toString())
                .claim("email", email)
                .claim("sid", sessionId.toString())
                .claim("uidx", userIndex)
                .claim("role", role != null ? role : "USER")
                .issuedAt(now)
                .expiration(validity)
                .signWith(keyProvider.getPrivateKey(), Jwts.SIG.RS256)
                .compact();
    }

    /**
     * 공개키로 Access Token 검증 및 Claims 추출
     */
    public Claims parseAndValidateToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(keyProvider.getPublicKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            log.warn("JWT token expired: {}", e.getMessage());
            throw new AuthException(ErrorCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Invalid JWT token: {}", e.getMessage());
            throw new AuthException(ErrorCode.INVALID_TOKEN);
        }
    }

    public UUID getUserId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }

    public UUID getSessionId(Claims claims) {
        return UUID.fromString(claims.get("sid", String.class));
    }

    public int getUserIndex(Claims claims) {
        return claims.get("uidx", Integer.class);
    }
}

package com.hunnit_beasts.auth.core.token;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.core.redis.KillSwitchPublisher;
import com.hunnit_beasts.auth.domain.session.entity.UserSession;
import com.hunnit_beasts.auth.domain.session.repository.UserSessionRepository;
import com.hunnit_beasts.auth.domain.token.entity.RefreshToken;
import com.hunnit_beasts.auth.domain.token.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserSessionRepository userSessionRepository;
    private final KillSwitchPublisher killSwitchPublisher;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${doro.iam.jwt.refresh-token-validity-seconds:1209600}")
    private long refreshTokenValiditySeconds;

    /**
     * 신규 세션에 대한 초기 Refresh Token 생성
     */
    @Transactional
    public String createRefreshToken(UUID sessionId) {
        String rawToken = generateSecureToken();
        String tokenHash = hashToken(rawToken);
        UUID familyId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(refreshTokenValiditySeconds, ChronoUnit.SECONDS);

        RefreshToken refreshToken = RefreshToken.builder()
                .sessionId(sessionId)
                .tokenHash(tokenHash)
                .familyId(familyId)
                .expiresAt(expiresAt)
                .build();

        refreshTokenRepository.save(refreshToken);
        return rawToken;
    }

    /**
     * Refresh Token Rotation (RTR) 수행 및 재사용 공격 탐지
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = AuthException.class)
    public RotatedTokenResult rotateRefreshToken(String rawToken) {
        String tokenHash = hashToken(rawToken);

        RefreshToken existingToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new AuthException(ErrorCode.INVALID_TOKEN));

        // 1. 재사용 공격 탐지 (이미 회전/폐기된 토큰이 다시 인입된 경우)
        if (existingToken.isRevoked()) {
            log.error("SECURITY ALERT: Refresh token reuse detected! FamilyId={}, SessionId={}",
                    existingToken.getFamilyId(), existingToken.getSessionId());

            // 탈취 위험: 해당 패밀리의 모든 토큰 무효화 & 세션 즉시 강제 종료
            refreshTokenRepository.revokeAllByFamilyId(existingToken.getFamilyId());
            refreshTokenRepository.flush();

            userSessionRepository.findById(existingToken.getSessionId())
                    .ifPresent(s -> {
                        s.deactivate();
                        userSessionRepository.saveAndFlush(s);
                    });

            // Redis 실시간 킬스위치 발행
            killSwitchPublisher.publishFamilyRevoked(null, existingToken.getFamilyId(), "REUSE_ATTACK_DETECTED");
            killSwitchPublisher.publishSessionRevoked(null, existingToken.getSessionId(), "REUSE_ATTACK_DETECTED");

            throw new AuthException(ErrorCode.TOKEN_REUSE_DETECTED);
        }

        // 2. 만료 여부 확인
        if (Instant.now().isAfter(existingToken.getExpiresAt())) {
            existingToken.revoke();
            refreshTokenRepository.saveAndFlush(existingToken);
            throw new AuthException(ErrorCode.TOKEN_EXPIRED);
        }

        // 3. 세션 유효성 확인
        UserSession session = userSessionRepository.findByIdAndIsActiveTrue(existingToken.getSessionId())
                .orElseThrow(() -> new AuthException(ErrorCode.SESSION_EXPIRED));

        if (session.isExpired()) {
            session.deactivate();
            throw new AuthException(ErrorCode.SESSION_EXPIRED);
        }

        // 4. 기존 토큰 폐기 (RTR)
        existingToken.revoke();

        // 5. 새 토큰 생성 (동일 Family 유지)
        String newRawToken = generateSecureToken();
        String newTokenHash = hashToken(newRawToken);
        Instant newExpiresAt = Instant.now().plus(refreshTokenValiditySeconds, ChronoUnit.SECONDS);

        RefreshToken newRefreshToken = RefreshToken.builder()
                .sessionId(session.getId())
                .tokenHash(newTokenHash)
                .familyId(existingToken.getFamilyId())
                .expiresAt(newExpiresAt)
                .build();

        refreshTokenRepository.save(newRefreshToken);
        session.touch();

        return new RotatedTokenResult(newRawToken, session);
    }

    @Transactional
    public void revokeAllForSession(UUID sessionId) {
        refreshTokenRepository.revokeAllBySessionId(sessionId);
    }

    private String generateSecureToken() {
        byte[] randomBytes = new byte[48];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    public record RotatedTokenResult(String newRefreshToken, UserSession session) {}
}

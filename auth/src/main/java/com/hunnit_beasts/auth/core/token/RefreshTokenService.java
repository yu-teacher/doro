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
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserSessionRepository userSessionRepository;
    private final KillSwitchPublisher killSwitchPublisher;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${doro.iam.jwt.refresh-token-validity-seconds:2592000}")
    private long refreshTokenValiditySeconds;

    /** 세션 무활동 만료 시간. 리프레시(활동)마다 세션 만료 시각이 now + 이 값으로 연장된다. */
    @Value("${doro.iam.session.inactivity-timeout-seconds:2592000}")
    private long sessionInactivityTimeoutSeconds;

    /**
     * 세션 절대 수명. 세션을 만든 시각(로그인)부터 이 시간이 지나면 계속 사용해도(슬라이딩 갱신) 더는 갱신되지 않고 다시 로그인해야 한다.
     * 탈취된 리프레시 토큰이나 잊힌 기기의 세션이 쓸 때마다 무한히 이어지는 것을 막는다. 0 이하면 끈다(무활동 만료만 적용).
     */
    @Value("${doro.iam.session.absolute-lifetime-seconds:7776000}")
    private long sessionAbsoluteLifetimeSeconds;

    /**
     * 회전 직후 이 시간(초) 안에 들어온 직전 토큰은 공격이 아니라 동시 요청(다중 탭 등)으로 보고 세션을 유지한다.
     * 기본값 0 은 비활성(즉시 재사용 공격으로 판정)이며, 다중 탭 경합이 문제될 때만 켠다.
     */
    @Value("${doro.iam.jwt.refresh-reuse-grace-seconds:0}")
    private long refreshReuseGraceSeconds;

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
            if (isConcurrentRotationRace(existingToken)) {
                log.warn("Refresh token presented right after rotation (treated as concurrent request): sessionId={}",
                        existingToken.getSessionId());
                throw new AuthException(ErrorCode.INVALID_TOKEN);
            }
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

        // 3-1. 세션 절대 수명: 만들어진 지 너무 오래된 세션은 활동이 있어도 갱신하지 않는다(액세스 토큰도 곧 쓸 수 없게 한다).
        Instant absoluteEnd = absoluteEndOf(session);
        if (absoluteEnd != null && !Instant.now().isBefore(absoluteEnd)) {
            session.deactivate();
            refreshTokenRepository.revokeAllBySessionId(session.getId());
            killSwitchPublisher.publishSessionRevoked(session.getUserId(), session.getId(), "SESSION_MAX_LIFETIME");
            log.info("Session reached its absolute lifetime and was ended: sessionId={}, createdAt={}", session.getId(), session.getCreatedAt());
            throw new AuthException(ErrorCode.SESSION_EXPIRED, "로그인 유지 기간이 지났습니다. 다시 로그인해 주세요.");
        }

        // 4. 기존 토큰 폐기 (RTR). 조건부 UPDATE 의 결과로 동시 요청 중 단 하나만 회전에 성공하게 한다.
        if (refreshTokenRepository.revokeIfActive(existingToken.getId()) == 0) {
            log.warn("Lost refresh token rotation race: sessionId={}", existingToken.getSessionId());
            throw new AuthException(ErrorCode.INVALID_TOKEN);
        }

        // 5. 새 토큰 생성 (동일 Family 유지)
        String newRawToken = generateSecureToken();
        String newTokenHash = hashToken(newRawToken);
        Instant newExpiresAt = Instant.now().plus(refreshTokenValiditySeconds, ChronoUnit.SECONDS);
        // 새 토큰도 세션의 절대 수명 끝을 넘겨 살아 있지 못하게 한다
        if (absoluteEnd != null && absoluteEnd.isBefore(newExpiresAt)) {
            newExpiresAt = absoluteEnd;
        }

        RefreshToken newRefreshToken = RefreshToken.builder()
                .sessionId(session.getId())
                .tokenHash(newTokenHash)
                .familyId(existingToken.getFamilyId())
                .expiresAt(newExpiresAt)
                .build();

        refreshTokenRepository.save(newRefreshToken);
        session.touch(Instant.now(), sessionInactivityTimeoutSeconds);

        return new RotatedTokenResult(newRawToken, session);
    }

    /** 세션이 절대 수명에 닿는 시각. 기능을 껐거나 생성 시각을 모르면 null. */
    private Instant absoluteEndOf(UserSession session) {
        if (sessionAbsoluteLifetimeSeconds <= 0 || session.getCreatedAt() == null) {
            return null;
        }
        return session.getCreatedAt().plusSeconds(sessionAbsoluteLifetimeSeconds);
    }

    private boolean isConcurrentRotationRace(RefreshToken revokedToken) {
        if (refreshReuseGraceSeconds <= 0) {
            return false;
        }
        return refreshTokenRepository.findFirstByFamilyIdAndIsRevokedFalseOrderByCreatedAtDesc(revokedToken.getFamilyId())
                .map(active -> active.getCreatedAt() != null
                        && Instant.now().isBefore(active.getCreatedAt().plusSeconds(refreshReuseGraceSeconds)))
                .orElse(false);
    }

    /**
     * 리프레시 토큰이 속한 세션을 회전 없이 조회한다(폐기/비활성 토큰·세션 포함). 회전 전에 세션 종류(OAuth 클라이언트 등)를
     * 검사해, 조건에 맞지 않는 토큰을 소모하지 않고 거부하기 위한 용도다.
     */
    @Transactional(readOnly = true)
    public Optional<UserSession> findSessionByRawToken(String rawToken) {
        return refreshTokenRepository.findByTokenHash(hashToken(rawToken))
                .flatMap(token -> userSessionRepository.findById(token.getSessionId()));
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

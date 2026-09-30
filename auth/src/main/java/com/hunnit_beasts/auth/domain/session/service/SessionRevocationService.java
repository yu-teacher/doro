package com.hunnit_beasts.auth.domain.session.service;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.core.redis.KillSwitchPublisher;
import com.hunnit_beasts.auth.core.token.RefreshTokenService;
import com.hunnit_beasts.auth.domain.session.entity.UserSession;
import com.hunnit_beasts.auth.domain.session.repository.UserSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 세션 종료의 단일 진입점. 소유자 검증 → 세션 비활성화 → 리프레시 토큰 폐기 → 킬스위치 발행을
 * 하나의 흐름으로 수행하여 DB 상태와 Redis 무효화가 항상 함께 이루어지게 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionRevocationService {

    private final SessionService sessionService;
    private final UserSessionRepository sessionRepository;
    private final RefreshTokenService refreshTokenService;
    private final KillSwitchPublisher killSwitchPublisher;

    /** 본인 소유의 활성 세션만 종료한다. 타인의 세션이면 존재 여부를 노출하지 않도록 SESSION_NOT_FOUND 로 응답한다. */
    @Transactional
    public void revokeOwnSession(UUID userId, UUID sessionId, String reason) {
        UserSession session = sessionService.getActiveSession(sessionId);
        if (!session.getUserId().equals(userId)) {
            log.warn("Rejected session revocation for a session not owned by the caller: userId={}, sessionId={}",
                    userId, sessionId);
            throw new AuthException(ErrorCode.SESSION_NOT_FOUND);
        }
        revoke(session, reason);
    }

    /** keepSessionId 를 제외한 사용자의 모든 활성 세션을 종료한다. keepSessionId 가 null 이면 전부 종료한다. */
    @Transactional
    public void revokeOtherSessions(UUID userId, UUID keepSessionId, String reason) {
        if (keepSessionId != null) {
            UserSession current = sessionService.getActiveSession(keepSessionId);
            if (!current.getUserId().equals(userId)) {
                throw new AuthException(ErrorCode.SESSION_NOT_FOUND);
            }
        }
        List<UserSession> targets = sessionRepository.findByUserIdAndIsActiveTrueOrderByCreatedAtDesc(userId).stream()
                .filter(s -> !s.getId().equals(keepSessionId))
                .toList();
        for (UserSession target : targets) {
            revoke(target, reason);
        }
        log.info("Revoked {} session(s) for user: userId={}, reason={}", targets.size(), userId, reason);
    }

    private void revoke(UserSession session, String reason) {
        session.deactivate();
        refreshTokenService.revokeAllForSession(session.getId());
        killSwitchPublisher.publishSessionRevoked(session.getUserId(), session.getId(), reason);
        log.info("Session revoked: sessionId={}, reason={}", session.getId(), reason);
    }
}

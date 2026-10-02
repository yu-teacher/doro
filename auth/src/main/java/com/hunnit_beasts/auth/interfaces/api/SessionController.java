package com.hunnit_beasts.auth.interfaces.api;

import com.hunnit_beasts.auth.common.response.ApiResponse;
import com.hunnit_beasts.auth.core.token.SessionClaims;
import com.hunnit_beasts.auth.domain.session.dto.SessionResponse;
import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.domain.session.service.SessionRevocationService;
import com.hunnit_beasts.auth.domain.session.service.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;
    private final SessionRevocationService sessionRevocationService;

    /**
     * 현재 토큰의 세션이 아직 유효한지 DB 기준으로 답한다(서브 서비스 SDK 의 폐기 확인용).
     * 유효하면 204, 아니면 401(SESSION_EXPIRED)/403(ACCOUNT_SUSPENDED). 세션을 갱신하지 않는다.
     */
    @GetMapping("/current")
    public ResponseEntity<Void> checkCurrentSession(
            @AuthenticationPrincipal UUID userId,
            Authentication authentication) {
        requireLogin(userId);
        sessionService.assertSessionLive(SessionClaims.currentSessionId(authentication));
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<SessionResponse>>> getActiveSessions(
            @AuthenticationPrincipal UUID userId) {
        requireLogin(userId);
        List<SessionResponse> sessions = sessionService.getActiveSessions(userId);
        return ResponseEntity.ok(ApiResponse.success(sessions));
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<ApiResponse<Void>> revokeSession(
            @AuthenticationPrincipal UUID userId,
            @PathVariable("sessionId") UUID sessionId) {
        requireLogin(userId);
        sessionRevocationService.revokeOwnSession(userId, sessionId, "SESSION_REVOKED");
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/revoke-others")
    public ResponseEntity<ApiResponse<Void>> revokeOtherSessions(
            @AuthenticationPrincipal UUID userId,
            @RequestParam("currentSessionId") UUID currentSessionId) {
        requireLogin(userId);
        sessionRevocationService.revokeOtherSessions(userId, currentSessionId, "OTHER_SESSIONS_REVOKED");
        return ResponseEntity.ok(ApiResponse.success());
    }

    private static void requireLogin(UUID userId) {
        if (userId == null) {
            throw new AuthException(ErrorCode.UNAUTHORIZED, "로그인이 필요한 요청입니다.");
        }
    }
}

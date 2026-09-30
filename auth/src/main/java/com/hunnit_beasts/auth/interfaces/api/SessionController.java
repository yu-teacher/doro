package com.hunnit_beasts.auth.interfaces.api;

import com.hunnit_beasts.auth.common.response.ApiResponse;
import com.hunnit_beasts.auth.domain.session.dto.SessionResponse;
import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.domain.session.service.SessionRevocationService;
import com.hunnit_beasts.auth.domain.session.service.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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

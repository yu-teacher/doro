package com.hunnit_beasts.auth.interfaces.api;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.domain.user.dto.BootstrapRequest;
import com.hunnit_beasts.auth.domain.user.service.BootstrapService;
import com.hunnit_beasts.auth.common.web.ClientIpResolver;
import com.hunnit_beasts.auth.common.web.RefreshTokenCookies;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.common.exception.FieldValidationException;
import com.hunnit_beasts.auth.common.response.ApiResponse;
import com.hunnit_beasts.auth.domain.auth.dto.*;
import com.hunnit_beasts.auth.domain.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import com.hunnit_beasts.auth.core.token.SessionClaims;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final BootstrapService bootstrapService;
    private final ClientIpResolver clientIpResolver;
    private final RefreshTokenCookies refreshCookies;

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<Map<String, UUID>>> signup(@Valid @RequestBody SignUpRequest request) {
        UUID userId = authService.signup(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(Map.of("userId", userId)));
    }

    @PostMapping("/lookup")
    public ResponseEntity<ApiResponse<AccountLookupResponse>> lookupAccount(
            @Valid @RequestBody AccountLookupRequest request) {
        AccountLookupResponse response = authService.lookupAccount(request.email());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {

        String ipAddress = clientIpResolver.resolve(servletRequest);
        String userAgent = servletRequest.getHeader("User-Agent");

        // 슬롯 오류는 로그인(세션 생성) 전에 거른다. 세션만 만들어 두고 400 을 돌려주면 쓸 수 없는 세션이 남는다.
        boolean cookieMode = refreshCookies.isCookieMode(servletRequest);
        int slot = cookieMode ? refreshCookies.slot(servletRequest) : -1;

        LoginResponse loginResponse = authService.login(request, ipAddress, userAgent);
        if (cookieMode && loginResponse.tokens() != null) {
            refreshCookies.issue(servletResponse, slot, loginResponse.tokens().refreshToken());
            loginResponse = LoginResponse.directSuccess(refreshCookies.withoutRefreshToken(loginResponse.tokens()));
        }
        return ResponseEntity.ok(ApiResponse.success(loginResponse));
    }

    /**
     * 첫 관리자 부트스트랩: 운영자가 환경변수에 둔 일회용 토큰을 아는 로그인 사용자가 본인을 최고 관리자로 승격한다.
     * 최고 관리자가 이미 있으면 항상 거부한다. 성공하면 모든 세션이 끝나므로 다시 로그인해야 새 역할이 적용된다.
     */
    @PostMapping("/bootstrap")
    public ResponseEntity<ApiResponse<Void>> bootstrapFirstAdmin(
            @AuthenticationPrincipal UUID userId,
            @RequestBody(required = false) BootstrapRequest request) {
        if (userId == null) {
            throw new AuthException(ErrorCode.UNAUTHORIZED, "로그인이 필요한 요청입니다.");
        }
        bootstrapService.claimSuperAdmin(userId, request == null ? null : request.token());
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiResponse.success());
    }

    @PostMapping("/2fa/setup")
    public ResponseEntity<ApiResponse<TotpSetupResponse>> setupTotp(
            @AuthenticationPrincipal UUID userId,
            @RequestBody(required = false) TotpSetupRequest request) {
        // 인증 여부를 본문 검증보다 먼저 판단한다(인증 없는 요청이 본문 오류 400 으로 보이지 않게).
        if (userId == null) {
            throw new AuthException(ErrorCode.UNAUTHORIZED, "로그인이 필요한 요청입니다.");
        }
        if (request == null || request.currentPassword() == null || request.currentPassword().isBlank()) {
            throw new FieldValidationException("currentPassword", "현재 비밀번호를 입력해 주세요.");
        }
        TotpSetupResponse response = authService.setupTotp(userId, request.currentPassword());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/2fa/verify")
    public ResponseEntity<ApiResponse<Void>> verifyTotp(
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody TotpVerifyRequest request) {
        if (userId == null) {
            throw new AuthException(ErrorCode.UNAUTHORIZED, "로그인이 필요한 요청입니다.");
        }
        authService.verifyTotp(userId, request.code());
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/2fa/disable")
    public ResponseEntity<ApiResponse<Void>> disableTotp(
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody TotpVerifyRequest request) {
        if (userId == null) {
            throw new AuthException(ErrorCode.UNAUTHORIZED, "로그인이 필요한 요청입니다.");
        }
        authService.disableTotp(userId, request.code());
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/2fa/login")
    public ResponseEntity<ApiResponse<TokenResponse>> loginWithTotp(
            @Valid @RequestBody TotpLoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {

        String ipAddress = clientIpResolver.resolve(servletRequest);
        String userAgent = servletRequest.getHeader("User-Agent");

        boolean cookieMode = refreshCookies.isCookieMode(servletRequest);
        int slot = cookieMode ? refreshCookies.slot(servletRequest) : -1;

        TokenResponse tokenResponse = authService.loginWithTotp(request, ipAddress, userAgent);
        if (cookieMode) {
            refreshCookies.issue(servletResponse, slot, tokenResponse.refreshToken());
            tokenResponse = refreshCookies.withoutRefreshToken(tokenResponse);
        }
        return ResponseEntity.ok(ApiResponse.success(tokenResponse));
    }

    @PostMapping("/token/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(
            @RequestBody(required = false) RefreshTokenRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        boolean cookieMode = refreshCookies.isCookieMode(servletRequest);
        int slot = cookieMode ? refreshCookies.slot(servletRequest) : -1;

        // 본문의 토큰이 우선이다(쿠키 방식으로 옮겨 가는 클라이언트가 이전 토큰을 한 번 보낸다). 없으면 쿠키 방식에서만 쿠키를 읽는다.
        // 쿠키에서 온 토큰은 커스텀 헤더가 이미 확인된 요청에서만 받으므로 다른 사이트의 요청으로 회전시킬 수 없다.
        String bodyToken = request == null ? null : request.refreshToken();
        String token = bodyToken != null && !bodyToken.isBlank()
                ? bodyToken
                : (cookieMode ? refreshCookies.read(servletRequest, slot).orElse(null) : null);
        if (token == null) {
            throw new AuthException(ErrorCode.INVALID_TOKEN, "리프레시 토큰이 없습니다.");
        }

        TokenResponse tokenResponse;
        try {
            tokenResponse = authService.refresh(new RefreshTokenRequest(token));
        } catch (AuthException e) {
            // 서버가 토큰을 거부했다면 낡은 쿠키가 남아 계속 실패하지 않게 지운다(일시적 서버 오류는 쿠키를 그대로 둔다).
            if (cookieMode && e.getErrorCode().getHttpStatus().is4xxClientError()) {
                refreshCookies.clear(servletResponse, slot);
            }
            throw e;
        }
        if (cookieMode) {
            refreshCookies.issue(servletResponse, slot, tokenResponse.refreshToken());
            tokenResponse = refreshCookies.withoutRefreshToken(tokenResponse);
        }
        return ResponseEntity.ok(ApiResponse.success(tokenResponse));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @AuthenticationPrincipal UUID userId,
            Authentication authentication,
            @RequestParam(value = "sessionId", required = false) UUID sessionId,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        if (userId == null) {
            throw new AuthException(ErrorCode.UNAUTHORIZED, "로그인이 필요한 요청입니다.");
        }
        UUID targetSessionId = sessionId != null ? sessionId : SessionClaims.currentSessionId(authentication);
        if (targetSessionId == null) {
            throw new AuthException(ErrorCode.INVALID_INPUT, "종료할 세션을 식별할 수 없습니다.");
        }
        authService.logout(userId, targetSessionId);
        if (refreshCookies.isCookieMode(servletRequest)) {
            refreshCookies.clear(servletResponse, refreshCookies.slot(servletRequest));
        }
        return ResponseEntity.ok(ApiResponse.success());
    }
}

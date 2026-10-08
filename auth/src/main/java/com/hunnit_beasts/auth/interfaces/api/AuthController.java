package com.hunnit_beasts.auth.interfaces.api;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.web.ClientIpResolver;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.common.exception.FieldValidationException;
import com.hunnit_beasts.auth.common.response.ApiResponse;
import com.hunnit_beasts.auth.domain.auth.dto.*;
import com.hunnit_beasts.auth.domain.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
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
    private final ClientIpResolver clientIpResolver;

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
            HttpServletRequest servletRequest) {

        String ipAddress = clientIpResolver.resolve(servletRequest);
        String userAgent = servletRequest.getHeader("User-Agent");

        LoginResponse loginResponse = authService.login(request, ipAddress, userAgent);
        return ResponseEntity.ok(ApiResponse.success(loginResponse));
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
            HttpServletRequest servletRequest) {

        String ipAddress = clientIpResolver.resolve(servletRequest);
        String userAgent = servletRequest.getHeader("User-Agent");

        TokenResponse tokenResponse = authService.loginWithTotp(request, ipAddress, userAgent);
        return ResponseEntity.ok(ApiResponse.success(tokenResponse));
    }

    @PostMapping("/token/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        TokenResponse tokenResponse = authService.refresh(request);
        return ResponseEntity.ok(ApiResponse.success(tokenResponse));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @AuthenticationPrincipal UUID userId,
            Authentication authentication,
            @RequestParam(value = "sessionId", required = false) UUID sessionId) {
        if (userId == null) {
            throw new AuthException(ErrorCode.UNAUTHORIZED, "로그인이 필요한 요청입니다.");
        }
        UUID targetSessionId = sessionId != null ? sessionId : SessionClaims.currentSessionId(authentication);
        if (targetSessionId == null) {
            throw new AuthException(ErrorCode.INVALID_INPUT, "종료할 세션을 식별할 수 없습니다.");
        }
        authService.logout(userId, targetSessionId);
        return ResponseEntity.ok(ApiResponse.success());
    }
}

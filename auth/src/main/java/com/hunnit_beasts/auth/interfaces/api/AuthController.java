package com.hunnit_beasts.auth.interfaces.api;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.common.response.ApiResponse;
import com.hunnit_beasts.auth.domain.auth.dto.*;
import com.hunnit_beasts.auth.domain.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

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

        String ipAddress = servletRequest.getRemoteAddr();
        String userAgent = servletRequest.getHeader("User-Agent");

        LoginResponse loginResponse = authService.login(request, ipAddress, userAgent);
        return ResponseEntity.ok(ApiResponse.success(loginResponse));
    }

    @PostMapping("/2fa/setup")
    public ResponseEntity<ApiResponse<TotpSetupResponse>> setupTotp(
            @AuthenticationPrincipal UUID userId) {
        if (userId == null) {
            throw new AuthException(ErrorCode.UNAUTHORIZED, "로그인이 필요한 요청입니다.");
        }
        TotpSetupResponse response = authService.setupTotp(userId);
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
            @AuthenticationPrincipal UUID userId) {
        if (userId == null) {
            throw new AuthException(ErrorCode.UNAUTHORIZED, "로그인이 필요한 요청입니다.");
        }
        authService.disableTotp(userId);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/2fa/login")
    public ResponseEntity<ApiResponse<TokenResponse>> loginWithTotp(
            @Valid @RequestBody TotpLoginRequest request,
            HttpServletRequest servletRequest) {

        String ipAddress = servletRequest.getRemoteAddr();
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
    public ResponseEntity<ApiResponse<Void>> logout(@RequestParam("sessionId") UUID sessionId) {
        authService.logout(sessionId);
        return ResponseEntity.ok(ApiResponse.success());
    }
}

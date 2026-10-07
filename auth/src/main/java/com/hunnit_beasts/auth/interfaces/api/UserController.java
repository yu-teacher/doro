package com.hunnit_beasts.auth.interfaces.api;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.common.response.ApiResponse;
import com.hunnit_beasts.auth.domain.user.dto.ChangePasswordRequest;
import com.hunnit_beasts.auth.domain.user.dto.DeleteAccountRequest;
import com.hunnit_beasts.auth.domain.user.dto.DeleteAccountResponse;
import com.hunnit_beasts.auth.domain.user.dto.UpdateProfileRequest;
import com.hunnit_beasts.auth.domain.user.dto.UserProfileResponse;
import com.hunnit_beasts.auth.domain.user.service.AccountDeletionService;
import com.hunnit_beasts.auth.domain.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import com.hunnit_beasts.auth.core.token.SessionClaims;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final AccountDeletionService accountDeletionService;

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserProfileResponse>> getProfile(
            @AuthenticationPrincipal UUID userId) {
        if (userId == null) {
            throw new AuthException(ErrorCode.UNAUTHORIZED, "로그인이 필요한 요청입니다.");
        }
        UserProfileResponse response = userService.getProfile(userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PatchMapping("/me")
    public ResponseEntity<ApiResponse<UserProfileResponse>> updateProfile(
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody UpdateProfileRequest request) {
        if (userId == null) {
            throw new AuthException(ErrorCode.UNAUTHORIZED, "로그인이 필요한 요청입니다.");
        }
        UserProfileResponse response = userService.updateProfile(userId, request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PutMapping("/me/password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @AuthenticationPrincipal UUID userId,
            Authentication authentication,
            @Valid @RequestBody ChangePasswordRequest request) {
        if (userId == null) {
            throw new AuthException(ErrorCode.UNAUTHORIZED, "로그인이 필요한 요청입니다.");
        }
        userService.changePassword(userId, request, SessionClaims.currentSessionId(authentication));
        return ResponseEntity.ok(ApiResponse.success());
    }

    /** 회원탈퇴 요청: 비밀번호(2FA 사용 시 코드)로 재확인하고, 즉시 로그아웃한 뒤 유예 기간 후 개인정보를 익명화한다. */
    @PostMapping("/me/deletion")
    public ResponseEntity<ApiResponse<DeleteAccountResponse>> requestDeletion(
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody DeleteAccountRequest request) {
        if (userId == null) {
            throw new AuthException(ErrorCode.UNAUTHORIZED, "로그인이 필요한 요청입니다.");
        }
        return ResponseEntity.ok(ApiResponse.success(accountDeletionService.requestDeletion(userId, request)));
    }
}

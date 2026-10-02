package com.hunnit_beasts.auth.interfaces.api;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.common.response.ApiResponse;
import com.hunnit_beasts.auth.domain.user.dto.UserProfileResponse;
import com.hunnit_beasts.auth.domain.user.entity.UserRole;
import com.hunnit_beasts.auth.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminController {

    private final UserService userService;

    /**
     * 관리자 전용 전체 사용자 목록 조회 (ADMIN, SUPER_ADMIN)
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<List<UserProfileResponse>>> getAllUsers(@AuthenticationPrincipal UUID adminId) {
        return ResponseEntity.ok(ApiResponse.success(userService.getAllUsers(adminId)));
    }

    /**
     * 슈퍼 어드민 전용 사용자 역할 변경 (SUPER_ADMIN)
     */
    @PatchMapping("/{userId}/role")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<UserProfileResponse>> updateUserRole(
            @PathVariable UUID userId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal UUID adminId
    ) {
        UserRole newRole = parseRole(body == null ? null : body.get("role"));
        UserProfileResponse updated = userService.changeUserRole(userId, newRole, adminId);
        return ResponseEntity.ok(ApiResponse.success(updated));
    }

    private static UserRole parseRole(String roleStr) {
        String allowed = Arrays.stream(UserRole.values()).map(Enum::name).collect(Collectors.joining(", "));
        if (roleStr == null || roleStr.isBlank()) {
            throw new AuthException(ErrorCode.INVALID_INPUT, "role 은 필수입니다. 허용 값: " + allowed);
        }
        try {
            return UserRole.valueOf(roleStr.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new AuthException(ErrorCode.INVALID_INPUT, "알 수 없는 role 입니다. 허용 값: " + allowed);
        }
    }

    /**
     * 관리자/슈퍼 어드민 전용 사용자 2FA 취소/초기화 (ADMIN, SUPER_ADMIN)
     */
    @DeleteMapping("/{userId}/2fa")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> resetUserTwoFactor(
            @PathVariable UUID userId,
            @AuthenticationPrincipal UUID adminId
    ) {
        userService.resetUserTwoFactor(userId, adminId);
        return ResponseEntity.ok(ApiResponse.success());
    }
}

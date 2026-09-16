package com.hunnit_beasts.auth.interfaces.api;

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

import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    public ResponseEntity<ApiResponse<List<UserProfileResponse>>> getAllUsers() {
        return ResponseEntity.ok(ApiResponse.success(userService.getAllUsers()));
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
        String roleStr = body.get("role");
        if (roleStr == null || roleStr.isBlank()) {
            throw new IllegalArgumentException("role is required");
        }
        UserRole newRole = UserRole.valueOf(roleStr.trim().toUpperCase());
        UserProfileResponse updated = userService.changeUserRole(userId, newRole, adminId);
        return ResponseEntity.ok(ApiResponse.success(updated));
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

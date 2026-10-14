package com.hunnit_beasts.auth.interfaces.api;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.common.response.ApiResponse;
import com.hunnit_beasts.auth.domain.user.dto.UserProfileResponse;
import com.hunnit_beasts.auth.domain.user.entity.UserRole;
import com.hunnit_beasts.auth.domain.user.service.AccountSuspensionService;
import com.hunnit_beasts.auth.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 500;

    private final UserService userService;
    private final AccountSuspensionService suspensionService;

    /**
     * 관리자 전용 사용자 목록 조회 (ADMIN, SUPER_ADMIN).
     * <p> {@code size} 를 주면 {@code page}(0부터)에 해당하는 페이지만 돌려주고, 전체 수는 {@code X-Total-Count} 헤더에 담는다.
     * 파라미터가 없으면 기존처럼 전체를 돌려준다(웹 관리자 화면 호환). 사용자가 많으면 페이지를 나눠 호출한다.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<List<UserProfileResponse>>> getAllUsers(
            @AuthenticationPrincipal UUID adminId,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        if (size == null && page == null) {
            return ResponseEntity.ok(ApiResponse.success(userService.getAllUsers(adminId)));
        }
        int p = page == null ? 0 : page;
        int s = size == null ? DEFAULT_PAGE_SIZE : size;
        if (p < 0 || s < 1 || s > MAX_PAGE_SIZE) {
            throw new AuthException(ErrorCode.INVALID_INPUT, "page 는 0 이상, size 는 1~" + MAX_PAGE_SIZE + " 이어야 합니다.");
        }
        Page<UserProfileResponse> result = userService.getUsers(adminId, PageRequest.of(p, s));
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(result.getTotalElements()))
                .body(ApiResponse.success(result.getContent()));
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

    /**
     * 계정 정지 (ADMIN, SUPER_ADMIN). 로그인·토큰 갱신을 막고 기존 세션을 모두 끝낸다. 사유는 필수(200자 이내).
     * 관리자 계정은 최고 관리자만, 최고 관리자와 본인은 정지할 수 없다.
     */
    @PutMapping("/{userId}/suspension")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<UserProfileResponse>> suspendUser(
            @PathVariable UUID userId,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal UUID adminId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                suspensionService.suspend(userId, body == null ? null : body.get("reason"), adminId)));
    }

    /** 정지 해제 (ADMIN, SUPER_ADMIN). */
    @DeleteMapping("/{userId}/suspension")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<UserProfileResponse>> reinstateUser(
            @PathVariable UUID userId,
            @AuthenticationPrincipal UUID adminId
    ) {
        return ResponseEntity.ok(ApiResponse.success(suspensionService.reinstate(userId, adminId)));
    }

    /** 비밀번호 연속 실패로 잠긴 계정의 잠금 해제 (ADMIN, SUPER_ADMIN). */
    @DeleteMapping("/{userId}/lock")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<UserProfileResponse>> unlockUser(
            @PathVariable UUID userId,
            @AuthenticationPrincipal UUID adminId
    ) {
        return ResponseEntity.ok(ApiResponse.success(suspensionService.unlock(userId, adminId)));
    }
}

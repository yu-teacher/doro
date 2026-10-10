package com.hunnit_beasts.auth.domain.user.dto;

import com.hunnit_beasts.auth.domain.user.entity.User;

import java.time.Instant;
import java.util.UUID;

/**
 * 사용자 프로필. 정지 정보(suspendedAt, suspensionReason)와 비밀번호 실패 잠금(locked)은 관리자 목록에서만 채운다.
 * 본인 프로필(/users/me)에는 담지 않는다(정지된 계정은 로그인할 수 없고, 잠금 상태는 로그인 오류로 알린다).
 */
public record UserProfileResponse(
        UUID id,
        String email,
        String name,
        String profileImageUrl,
        String status,
        String role,
        boolean hasTotp,
        Instant createdAt,
        Instant suspendedAt,
        String suspensionReason,
        boolean locked
) {
    public UserProfileResponse(UUID id, String email, String name, String profileImageUrl, String status,
                               String role, boolean hasTotp, Instant createdAt) {
        this(id, email, name, profileImageUrl, status, role, hasTotp, createdAt, null, null, false);
    }

    /** 관리자 화면용: 정지 정보와 잠금 상태를 포함한다. */
    public static UserProfileResponse forAdmin(User user, boolean hasTotp, boolean locked) {
        return new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getProfileImageUrl(),
                user.getStatus().name(),
                user.getRole() != null ? user.getRole().name() : "USER",
                hasTotp,
                user.getCreatedAt(),
                user.getSuspendedAt(),
                user.getSuspensionReason(),
                locked
        );
    }
}

package com.hunnit_beasts.auth.domain.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        // 비밀번호를 정한 적 없는 소셜 가입 계정은 비워 보낸다. 필요한 계정에서는 서비스가 비어 있으면 거부한다.
        @Size(max = 128, message = "비밀번호가 너무 깁니다.")
        String currentPassword,

        @NotBlank(message = "새로운 비밀번호를 입력해 주세요.")
        @Size(min = 8, max = 64, message = "비밀번호는 최소 8자 이상이어야 합니다.")
        String newPassword
) {
}

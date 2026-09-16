package com.hunnit_beasts.auth.domain.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank(message = "현재 비밀번호를 입력해 주세요.")
        String currentPassword,

        @NotBlank(message = "새로운 비밀번호를 입력해 주세요.")
        @Size(min = 8, max = 64, message = "비밀번호는 최소 8자 이상이어야 합니다.")
        String newPassword
) {
}

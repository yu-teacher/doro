package com.hunnit_beasts.auth.domain.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "이메일은 필수 입력값입니다.")
        @Email(message = "올바른 이메일 형식이 아닙니다.")
        @Size(max = 255, message = "이메일은 255자 이하이어야 합니다.")
        String email,

        @NotBlank(message = "비밀번호는 필수 입력값입니다.")
        @Size(max = 128, message = "비밀번호가 너무 깁니다.")
        String password,

        @Size(max = 255, message = "기기 정보는 255자 이하이어야 합니다.")
        String deviceInfo
) {
}

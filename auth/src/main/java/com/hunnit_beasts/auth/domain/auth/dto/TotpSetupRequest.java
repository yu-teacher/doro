package com.hunnit_beasts.auth.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** 2FA 등록 시작 요청. 액세스 토큰만 탈취된 경우 공격자 기기가 등록되지 않도록 현재 비밀번호로 다시 확인한다. */
public record TotpSetupRequest(
        @NotBlank(message = "현재 비밀번호를 입력해 주세요.")
        String currentPassword
) {
}

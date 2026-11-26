package com.hunnit_beasts.auth.domain.auth.dto;

import jakarta.validation.constraints.Size;

/**
 * 2FA 등록 시작 요청. 액세스 토큰만 탈취된 경우 공격자 기기가 등록되지 않도록 현재 비밀번호로 다시 확인한다.
 * 비밀번호를 정한 적 없는 소셜 가입 계정은 보낼 비밀번호가 없으므로 여기서 필수로 막지 않는다.
 * 필수 여부는 계정 상태를 아는 AuthService.setupTotp 가 판단한다(비밀번호가 있는 계정이 비우면 400).
 */
public record TotpSetupRequest(
        @Size(max = 128, message = "비밀번호가 너무 깁니다.")
        String currentPassword
) {
}

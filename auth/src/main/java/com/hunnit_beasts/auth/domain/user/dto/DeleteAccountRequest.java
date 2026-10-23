package com.hunnit_beasts.auth.domain.user.dto;

import jakarta.validation.constraints.Size;

/** 회원탈퇴 요청. 액세스 토큰만으로는 탈퇴할 수 없도록 비밀번호(와 2FA 사용 시 OTP 코드)로 다시 확인한다. */
public record DeleteAccountRequest(
        // 비밀번호를 정한 적 없는 소셜 가입 계정은 비워 보낸다. 필요한 계정에서는 서비스가 비어 있으면 거부한다.
        @Size(max = 128, message = "비밀번호가 너무 깁니다.")
        String password,

        @Size(max = 16, message = "인증 코드가 너무 깁니다.")
        String totpCode
) {
}

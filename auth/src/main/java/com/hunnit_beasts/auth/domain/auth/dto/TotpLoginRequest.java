package com.hunnit_beasts.auth.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record TotpLoginRequest(
        @NotBlank(message = "임시 인증 티켓은 필수입니다.")
        @Size(max = 512, message = "임시 인증 티켓이 너무 깁니다.")
        String tempTicket,

        @NotBlank(message = "인증 코드는 필수입니다.")
        @Pattern(regexp = "^[0-9]{6}$", message = "인증 코드는 6자리 숫자여야 합니다.")
        String code,

        @Size(max = 255, message = "기기 정보는 255자 이하이어야 합니다.")
        String deviceInfo
) {
}

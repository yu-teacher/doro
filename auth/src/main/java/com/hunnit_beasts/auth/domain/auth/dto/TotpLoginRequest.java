package com.hunnit_beasts.auth.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record TotpLoginRequest(
        @NotBlank(message = "임시 인증 티켓은 필수입니다.")
        String tempTicket,

        @NotBlank(message = "인증 코드는 필수입니다.")
        @Pattern(regexp = "^[0-9]{6}$", message = "인증 코드는 6자리 숫자여야 합니다.")
        String code,

        String deviceInfo
) {
}

package com.hunnit_beasts.auth.interfaces.api;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.infrastructure.guard.GuardClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 게이트웨이(nginx auth_request)가 관리자 전용 리소스(예: 로그 조회 /loki/)를 보호하기 위해 호출하는 인가 확인 엔드포인트.
 * JWT 역할 클레임은 최대 토큰 수명만큼 낡을 수 있으므로, 최종 판정은 Doro Guard(system:doro#admin)에 위임한다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/authz")
@RequiredArgsConstructor
public class AdminAuthzController {

    private final GuardClient guardClient;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<Void> authorizeAdmin(@AuthenticationPrincipal UUID userId) {
        if (userId == null || !guardClient.check("system", "doro", "admin", userId.toString())) {
            log.warn("Admin authorization denied by Guard: userId={}", userId);
            throw new AuthException(ErrorCode.ACCESS_DENIED);
        }
        return ResponseEntity.noContent().build();
    }
}

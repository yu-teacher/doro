package com.hunnit_beasts.auth.interfaces.api;

import com.hunnit_beasts.auth.common.response.ApiResponse;
import com.hunnit_beasts.auth.domain.oauth.dto.OAuthClientCreateRequest;
import com.hunnit_beasts.auth.domain.oauth.dto.OAuthClientResponse;
import com.hunnit_beasts.auth.domain.oauth.service.OAuthClientAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * OAuth 클라이언트 레지스트리 관리 API (ADMIN 역할 + Guard system:doro#admin).
 * 요청 본문 검증은 인가(Guard) 판정 뒤에 서비스에서 수행한다. 권한 없는 호출자가 검증 오류로 정보를 얻지 못하게 하기 위해서다.
 */
@RestController
@RequestMapping("/api/v1/admin/oauth/clients")
@RequiredArgsConstructor
public class AdminOAuthClientController {

    private final OAuthClientAdminService clientAdminService;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<OAuthClientResponse>> create(
            @RequestBody(required = false) OAuthClientCreateRequest request,
            @AuthenticationPrincipal UUID adminId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(clientAdminService.create(adminId, request)));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<List<OAuthClientResponse>>> list(@AuthenticationPrincipal UUID adminId) {
        return ResponseEntity.ok(ApiResponse.success(clientAdminService.list(adminId)));
    }

    @DeleteMapping("/{clientId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deactivate(
            @PathVariable String clientId,
            @AuthenticationPrincipal UUID adminId) {
        clientAdminService.deactivate(adminId, clientId);
        return ResponseEntity.ok(ApiResponse.success());
    }
}

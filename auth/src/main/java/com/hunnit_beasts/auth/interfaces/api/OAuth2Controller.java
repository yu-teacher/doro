package com.hunnit_beasts.auth.interfaces.api;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.common.response.ApiResponse;
import com.hunnit_beasts.auth.domain.auth.dto.TokenResponse;
import com.hunnit_beasts.auth.domain.oauth.dto.OAuth2TokenRequest;
import com.hunnit_beasts.auth.domain.oauth.service.OAuth2Service;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequiredArgsConstructor
public class OAuth2Controller {

    /** RFC 7636 S256 code_challenge: base64url 43자 */
    private static final Pattern PKCE_CHALLENGE = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private final OAuth2Service oAuth2Service;

    /**
     * OAuth 2.1 인가 엔드포인트: 사용자가 로그인된 상태에서 PKCE code_challenge와 함께 호출
     */
    @GetMapping("/oauth2/authorize")
    public ResponseEntity<ApiResponse<Map<String, String>>> authorize(
            @RequestParam("client_id") String clientId,
            @RequestParam("redirect_uri") String redirectUri,
            @RequestParam("response_type") String responseType,
            @RequestParam(value = "code_challenge") String codeChallenge,
            @RequestParam(value = "state", required = false) String state,
            @AuthenticationPrincipal UUID userId) {

        if (userId == null) {
            throw new AuthException(ErrorCode.UNAUTHORIZED, "로그인이 필요한 요청입니다.");
        }
        if (!"code".equals(responseType)) {
            throw new AuthException(ErrorCode.INVALID_INPUT, "response_type 은 code 만 지원합니다.");
        }
        if (!PKCE_CHALLENGE.matcher(codeChallenge).matches()) {
            throw new AuthException(ErrorCode.INVALID_INPUT, "code_challenge 형식이 올바르지 않습니다.");
        }

        String code = oAuth2Service.generateAuthorizationCode(clientId, redirectUri, userId, codeChallenge);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "code", code,
                "state", state != null ? state : ""
        )));
    }

    /**
     * OAuth 2.1 토큰 교환 엔드포인트: 인가 코드와 code_verifier로 토큰 획득
     */
    @PostMapping(value = "/oauth2/token", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<TokenResponse>> exchangeTokenJson(
            @Valid @RequestBody OAuth2TokenRequest request) {
        TokenResponse tokenResponse = oAuth2Service.exchangeCode(request);
        return ResponseEntity.ok(ApiResponse.success(tokenResponse));
    }

    /**
     * OpenID Connect Discovery 메타데이터 엔드포인트
     */
    @GetMapping(value = "/.well-known/openid-configuration", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getOpenIdConfiguration() {
        return ResponseEntity.ok(oAuth2Service.getOidcConfiguration());
    }
}

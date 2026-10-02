package com.hunnit_beasts.auth.interfaces.api;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.common.response.ApiResponse;
import com.hunnit_beasts.auth.domain.auth.dto.TokenResponse;
import com.hunnit_beasts.auth.domain.oauth.dto.OAuth2TokenRequest;
import com.hunnit_beasts.auth.domain.oauth.service.OAuth2Constants;
import com.hunnit_beasts.auth.domain.oauth.service.OAuth2ErrorType;
import com.hunnit_beasts.auth.domain.oauth.service.OAuth2Exception;
import com.hunnit_beasts.auth.domain.oauth.service.OAuth2Service;
import com.hunnit_beasts.auth.domain.oauth.service.OAuthClientRegistry.ResolvedClient;
import com.hunnit_beasts.auth.domain.session.entity.UserSession;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
public class OAuth2Controller {

    private static final String BEARER_PREFIX = "Bearer ";

    private final OAuth2Service oAuth2Service;

    /** 브라우저가 Bearer 없이 인가 엔드포인트로 오면 보낼 동의(로그인) 페이지 */
    @Value("${doro.oauth.consent-url:/oauth2/consent}")
    private String consentUrl;

    /**
     * OAuth 2.1 인가 엔드포인트.
     * <ul>
     *   <li>Bearer 있음(포털 동의 화면의 fetch): 기존 JSON 계약 {@code {success,data:{code,state}}}</li>
     *   <li>Bearer 없음(브라우저 이동): client_id/redirect_uri 를 먼저 검증하고(실패 시 400 JSON, 절대 리다이렉트 없음),
     *       이후 오류는 redirect_uri 로 RFC 6749 §4.1.2.1 오류 리다이렉트, 정상이면 동의 페이지로 302</li>
     * </ul>
     */
    @GetMapping("/oauth2/authorize")
    public ResponseEntity<?> authorize(
            HttpServletRequest request,
            @RequestParam(value = "client_id", required = false) String clientId,
            @RequestParam(value = "redirect_uri", required = false) String redirectUri,
            @RequestParam(value = "response_type", required = false) String responseType,
            @RequestParam(value = "code_challenge", required = false) String codeChallenge,
            @RequestParam(value = "code_challenge_method", required = false) String codeChallengeMethod,
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "nonce", required = false) String nonce,
            Authentication authentication,
            @AuthenticationPrincipal UUID userId) {

        boolean bearerPresented = hasBearer(request);
        if (bearerPresented && userId == null) {
            throw new AuthException(ErrorCode.UNAUTHORIZED, "로그인이 필요한 요청입니다.");
        }

        // 1) client_id / redirect_uri: 실패하면 JSON 오류만 반환하고 redirect_uri 로는 절대 보내지 않는다(오픈 리다이렉트 방지).
        ResolvedClient client = oAuth2Service.validateClientAndRedirect(clientId, redirectUri);

        if (bearerPresented) {
            String grantedScope = oAuth2Service.validateAuthorizationParameters(
                    client, responseType, codeChallenge, codeChallengeMethod, scope, state, nonce);
            UserSession session = oAuth2Service.requireActiveSession(sessionIdOf(authentication));
            String code = oAuth2Service.issueAuthorizationCode(client, redirectUri, userId, codeChallenge,
                    grantedScope, nonce, session.getCreatedAt().getEpochSecond());
            return noStore(ResponseEntity.ok()).body(ApiResponse.success(Map.of(
                    "code", code,
                    "state", state != null ? state : ""
            )));
        }

        // 브라우저 흐름: 나머지 파라미터 오류는 redirect_uri 로 돌려보낸다.
        try {
            oAuth2Service.validateAuthorizationParameters(
                    client, responseType, codeChallenge, codeChallengeMethod, scope, state, nonce);
        } catch (OAuth2Exception e) {
            return redirectWithError(redirectUri, e, state);
        }
        return redirectToConsent(request);
    }

    /** JSON(camelCase) 토큰 요청 — 기존 ApiResponse 계약 유지. */
    @PostMapping(value = "/oauth2/token", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<TokenResponse>> exchangeTokenJson(
            @Valid @RequestBody OAuth2TokenRequest request) {
        TokenResponse tokenResponse = oAuth2Service.token(request);
        return noStore(ResponseEntity.ok()).body(ApiResponse.success(tokenResponse));
    }

    /** RFC 6749 폼(snake_case) 토큰 요청 — RFC 6749 §5.1/§5.2 응답 형식. */
    @PostMapping(value = "/oauth2/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> exchangeTokenForm(HttpServletRequest request) {
        try {
            requireBodyParametersOnly(request);
            OAuth2TokenRequest tokenRequest = new OAuth2TokenRequest(
                    param(request, "grant_type"),
                    param(request, "code"),
                    param(request, "redirect_uri"),
                    param(request, "client_id"),
                    param(request, "code_verifier"),
                    param(request, "refresh_token"),
                    param(request, "scope"));
            if (tokenRequest.grantType() == null || tokenRequest.grantType().isBlank()) {
                throw new OAuth2Exception(OAuth2ErrorType.INVALID_REQUEST, "grant_type 은 필수입니다.");
            }
            TokenResponse response = oAuth2Service.token(tokenRequest);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("access_token", response.accessToken());
            body.put("token_type", "Bearer");
            body.put("expires_in", response.expiresIn());
            body.put("refresh_token", response.refreshToken());
            if (response.scope() != null) {
                body.put("scope", response.scope());
            }
            if (response.idToken() != null) {
                body.put("id_token", response.idToken());
            }
            return noStore(ResponseEntity.ok()).body(body);
        } catch (OAuth2Exception e) {
            return oauthError(e.getType(), e.getMessage());
        } catch (AuthException e) {
            if (e.getErrorCode() == ErrorCode.USER_NOT_FOUND || e.getErrorCode() == ErrorCode.ACCOUNT_SUSPENDED) {
                return oauthError(OAuth2ErrorType.INVALID_GRANT, "사용자 계정을 사용할 수 없습니다.");
            }
            throw e;
        }
    }

    /** OIDC UserInfo (Bearer 액세스 토큰). */
    @GetMapping(value = "/oauth2/userinfo", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> userInfo(Authentication authentication,
                                                        @AuthenticationPrincipal UUID userId) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                    .build();
        }
        oAuth2Service.requireActiveSession(sessionIdOf(authentication));
        return noStore(ResponseEntity.ok()).body(oAuth2Service.userInfo(userId));
    }

    /**
     * OpenID Connect Discovery 메타데이터 엔드포인트
     */
    @GetMapping(value = "/.well-known/openid-configuration", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getOpenIdConfiguration() {
        return ResponseEntity.ok(oAuth2Service.getOidcConfiguration());
    }

    // ------------------------------------------------------------------ 보조

    private static boolean hasBearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header != null && header.startsWith(BEARER_PREFIX);
    }

    private static UUID sessionIdOf(Authentication authentication) {
        if (authentication != null && authentication.getDetails() instanceof Claims claims) {
            String sid = claims.get("sid", String.class);
            if (sid != null) {
                try {
                    return UUID.fromString(sid);
                } catch (IllegalArgumentException e) {
                    log.warn("Access token carries a malformed sid claim");
                }
            }
        }
        throw new AuthException(ErrorCode.UNAUTHORIZED, "세션 정보를 확인할 수 없습니다.");
    }

    private ResponseEntity<Void> redirectToConsent(HttpServletRequest request) {
        String rawQuery = request.getQueryString();
        String location = consentUrl + (rawQuery == null || rawQuery.isEmpty() ? ""
                : (consentUrl.contains("?") ? "&" : "?") + rawQuery);
        if (location.indexOf('\r') >= 0 || location.indexOf('\n') >= 0) {
            throw new AuthException(ErrorCode.INVALID_INPUT, "요청 형식이 올바르지 않습니다.");
        }
        return noStore(ResponseEntity.status(HttpStatus.FOUND)).header(HttpHeaders.LOCATION, location).build();
    }

    /** RFC 6749 §4.1.2.1: redirect_uri 에 error, error_description, state 를 쿼리로 붙여 302. 값은 모두 URL 인코딩한다. */
    private ResponseEntity<Void> redirectWithError(String redirectUri, OAuth2Exception e, String state) {
        StringBuilder location = new StringBuilder(redirectUri)
                .append(redirectUri.contains("?") ? '&' : '?')
                .append("error=").append(encode(e.getType().getCode()))
                .append("&error_description=").append(encode(e.getMessage()));
        if (state != null && !state.isEmpty() && state.length() <= OAuth2Constants.MAX_STATE_LENGTH) {
            location.append("&state=").append(encode(state));
        }
        return noStore(ResponseEntity.status(HttpStatus.FOUND)).header(HttpHeaders.LOCATION, location.toString()).build();
    }

    private static String encode(String value) {
        // 공백을 '+' 가 아닌 %20 으로 내보내 일반 URI 파서와 폼 파서 모두에서 같은 값으로 읽히게 한다.
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static ResponseEntity<Map<String, Object>> oauthError(OAuth2ErrorType type, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", type.getCode());
        body.put("error_description", description);
        return noStore(ResponseEntity.status(type.getHttpStatus())).body(body);
    }

    private static ResponseEntity.BodyBuilder noStore(ResponseEntity.BodyBuilder builder) {
        return builder
                .header(HttpHeaders.CACHE_CONTROL, OAuth2Constants.CACHE_CONTROL_NO_STORE)
                .header(HttpHeaders.PRAGMA, OAuth2Constants.PRAGMA_NO_CACHE);
    }

    /** RFC 6749 §3.2: 토큰 요청 파라미터는 본문으로만 받고, 같은 이름을 두 번 보내면 거부한다. */
    private static void requireBodyParametersOnly(HttpServletRequest request) {
        if (request.getQueryString() != null && !request.getQueryString().isEmpty()) {
            throw new OAuth2Exception(OAuth2ErrorType.INVALID_REQUEST, "토큰 요청 파라미터는 본문(form)으로만 보낼 수 있습니다.");
        }
        for (String[] values : request.getParameterMap().values()) {
            if (values.length > 1) {
                throw new OAuth2Exception(OAuth2ErrorType.INVALID_REQUEST, "중복된 파라미터가 있습니다.");
            }
        }
    }

    private static String param(HttpServletRequest request, String name) {
        return request.getParameter(name);
    }
}

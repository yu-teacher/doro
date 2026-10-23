package com.hunnit_beasts.auth.interfaces.api;

import com.hunnit_beasts.auth.common.response.ApiResponse;
import com.hunnit_beasts.auth.common.web.ClientIpResolver;
import com.hunnit_beasts.auth.common.web.RefreshTokenCookies;
import com.hunnit_beasts.auth.domain.social.provider.SocialLoginException;
import com.hunnit_beasts.auth.domain.social.provider.SocialProvider;
import com.hunnit_beasts.auth.domain.social.service.SocialLoginService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 소셜 로그인 진입점(브라우저 이동 방식).
 * 로그인 화면 → /{provider}/start → 공급자 → /{provider}/callback → 로그인 화면(/login?social=...).
 * 성공하면 리프레시 토큰을 HttpOnly 쿠키(슬롯)로 심고, 화면이 그 쿠키로 액세스 토큰을 받아 간다(토큰이 주소에 실리지 않는다).
 * 실패·취소는 모두 /login?social=error&code=... 로 돌려보낸다(JSON 이 아니라 브라우저가 보는 화면으로 끝낸다).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth/social")
@RequiredArgsConstructor
public class SocialAuthController {

    /** 시작할 때 심고 콜백에서 확인하는 쿠키. 다른 사람이 만든 로그인 요청을 이 브라우저에 끼워 넣지 못하게 한다(로그인 CSRF). */
    static final String STATE_COOKIE = "doro_sso_state";
    private static final String COOKIE_PATH = "/api/v1/auth/social";

    private final SocialLoginService socialLoginService;
    private final ClientIpResolver clientIpResolver;
    private final RefreshTokenCookies refreshCookies;

    @Value("${doro.iam.refresh-cookie.secure:true}")
    private boolean secureCookie;

    /** 로그인 화면에 보일 수 있는 공급자 목록. */
    @GetMapping("/providers")
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> providers() {
        List<Map<String, String>> list = socialLoginService.enabledProviders().stream()
                .map(p -> Map.of("id", p.id(), "name", p.displayName()))
                .toList();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(ApiResponse.success(list));
    }

    @GetMapping("/{provider}/start")
    public ResponseEntity<Void> start(@PathVariable String provider, @RequestParam(required = false) String slot) {
        int slotNumber;
        try {
            slotNumber = Integer.parseInt(slot == null ? "" : slot.trim());
            if (slotNumber < 0 || slotNumber > RefreshTokenCookies.MAX_SLOT) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            return toLogin("error", Map.of("code", SocialLoginException.PROVIDER_ERROR));
        }
        try {
            SocialLoginService.Started started = socialLoginService.start(provider, slotNumber);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, started.redirect().toString())
                    .header(HttpHeaders.SET_COOKIE, stateCookie(started.state(), Duration.ofMinutes(10)).toString())
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .build();
        } catch (SocialLoginException e) {
            return toLogin("error", Map.of("code", e.getCode()));
        }
    }

    @GetMapping("/{provider}/callback")
    public ResponseEntity<Void> callback(@PathVariable String provider,
                                         @RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error,
                                         HttpServletRequest request,
                                         HttpServletResponse response) {
        // 시작할 때 이 브라우저에 심은 값과 state 가 같아야 한다. 어떤 결과든 쿠키는 한 번 쓰고 지운다.
        boolean sameBrowser = state != null && state.equals(readStateCookie(request));
        response.addHeader(HttpHeaders.SET_COOKIE, stateCookie("", Duration.ZERO).toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");

        if (!sameBrowser) {
            return toLogin("error", Map.of("code", SocialLoginException.INVALID_STATE));
        }
        if (error != null) {
            // 사용자가 공급자 화면에서 취소한 경우(access_denied)가 대부분이다. state 는 쓰지 않았으니 정리한다.
            socialLoginService.discard(state);
            return toLogin("error", Map.of("code", "access_denied".equals(error) ? SocialLoginException.DENIED : SocialLoginException.PROVIDER_ERROR));
        }

        try {
            SocialLoginService.Outcome outcome = socialLoginService.complete(
                    provider, code, state, clientIpResolver.resolve(request), request.getHeader("User-Agent"));
            if (outcome.twoFactorTicket() != null) {
                return toLogin("2fa", Map.of("ticket", outcome.twoFactorTicket(), "slot", String.valueOf(outcome.slot())));
            }
            refreshCookies.issue(response, outcome.slot(), outcome.tokens().refreshToken());
            return toLogin("ok", Map.of("slot", String.valueOf(outcome.slot())));
        } catch (SocialLoginException e) {
            log.warn("Social login failed: provider={}, code={}, reason={}", provider, e.getCode(), e.getMessage());
            return toLogin("error", Map.of("code", e.getCode()));
        } catch (RuntimeException e) {
            log.error("Social login failed unexpectedly: provider={}", provider, e);
            return toLogin("error", Map.of("code", SocialLoginException.PROVIDER_ERROR));
        }
    }

    private static ResponseEntity<Void> toLogin(String result, Map<String, String> params) {
        UriComponentsBuilder target = UriComponentsBuilder.fromPath("/login").queryParam("social", result);
        params.forEach(target::queryParam);
        // 상대 경로로 돌려준다(게이트웨이 뒤에서 내부 호스트 이름이 새지 않게).
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, target.build().encode().toUriString())
                .build();
    }

    private ResponseCookie stateCookie(String value, Duration age) {
        // Lax: 공급자 사이트에서 돌아오는 최상위 이동에는 실려야 한다(Strict 는 실리지 않는다).
        return ResponseCookie.from(STATE_COOKIE, value)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path(COOKIE_PATH)
                .maxAge(age)
                .build();
    }

    private static String readStateCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (STATE_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}

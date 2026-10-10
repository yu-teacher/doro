package com.hunnit_beasts.auth.common.web;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.domain.auth.dto.TokenResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 포털(브라우저)의 리프레시 토큰을 JavaScript 가 읽을 수 없는 HttpOnly 쿠키로 주고받게 한다.
 * 같은 도메인의 어느 서비스에서 XSS 가 나도 30일짜리 리프레시 토큰을 빼내 갈 수 없게 하려는 것이다.
 *
 * <p>쿠키 방식은 클라이언트가 {@value #HEADER_COOKIE_SESSION}: 1 헤더를 보낼 때만 켜진다. 보내지 않으면 이전과 같이 토큰이 응답
 * 본문에 있고 쿠키는 쓰지 않는다(CLI 등 다른 클라이언트와 호환). 포털은 여러 계정을 동시에 로그인해 두므로 계정마다 슬롯(0~{@value #MAX_SLOT})을
 * 정해 {@value #HEADER_SLOT} 헤더로 알리고, 쿠키 이름은 슬롯별({@code doro_rt_<슬롯>})이다.
 *
 * <p>CSRF: 쿠키는 SameSite=Strict 이고 Path 가 {@value #COOKIE_PATH} 라 인증 API 에만 실린다. 거기에 더해 쿠키로 전달된 토큰은
 * 커스텀 헤더(다른 사이트에서는 사전 요청 없이 보낼 수 없다)가 있어야만 받는다.
 */
@Component
public class RefreshTokenCookies {

    public static final String HEADER_COOKIE_SESSION = "X-Doro-Cookie-Session";
    public static final String HEADER_SLOT = "X-Doro-Account-Slot";
    public static final String COOKIE_PREFIX = "doro_rt_";
    public static final String COOKIE_PATH = "/api/v1/auth";
    /** 한 브라우저에 동시에 둘 수 있는 계정 수(슬롯 0~4). */
    public static final int MAX_SLOT = 4;

    private final boolean secure;
    private final Duration maxAge;

    public RefreshTokenCookies(
            @Value("${doro.iam.refresh-cookie.secure:true}") boolean secure,
            @Value("${doro.iam.jwt.refresh-token-validity-seconds:2592000}") long refreshTokenValiditySeconds) {
        this.secure = secure;
        this.maxAge = Duration.ofSeconds(refreshTokenValiditySeconds);
    }

    /** 이 요청이 쿠키 방식을 쓰는지. */
    public boolean isCookieMode(HttpServletRequest request) {
        return "1".equals(request.getHeader(HEADER_COOKIE_SESSION));
    }

    /** 계정 슬롯. 쿠키 방식인데 값이 없거나 범위를 벗어나면 400. */
    public int slot(HttpServletRequest request) {
        String raw = request.getHeader(HEADER_SLOT);
        try {
            int slot = Integer.parseInt(raw == null ? "" : raw.trim());
            if (slot >= 0 && slot <= MAX_SLOT) {
                return slot;
            }
        } catch (NumberFormatException ignored) {
            // 아래에서 같은 오류로 처리한다
        }
        throw new AuthException(ErrorCode.INVALID_INPUT, "계정 슬롯(" + HEADER_SLOT + ")이 올바르지 않습니다.");
    }

    /** 슬롯의 쿠키 값. */
    public Optional<String> read(HttpServletRequest request, int slot) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        String name = cookieName(slot);
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    public void issue(HttpServletResponse response, int slot, String refreshToken) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(slot, refreshToken, maxAge).toString());
    }

    public void clear(HttpServletResponse response, int slot) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(slot, "", Duration.ZERO).toString());
    }

    /** 쿠키로 보낸 리프레시 토큰은 응답 본문에서 뺀다(JavaScript 가 볼 필요가 없다). */
    public TokenResponse withoutRefreshToken(TokenResponse tokens) {
        return new TokenResponse(tokens.accessToken(), null, tokens.tokenType(), tokens.expiresIn(),
                tokens.sessionId(), tokens.userIndex(), tokens.idToken(), tokens.scope());
    }

    private ResponseCookie build(int slot, String value, Duration age) {
        return ResponseCookie.from(cookieName(slot), value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(age)
                .build();
    }

    private static String cookieName(int slot) {
        return COOKIE_PREFIX + slot;
    }
}

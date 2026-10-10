package com.hunnit_beasts.auth.domain.auth.dto;

/**
 * @param refreshToken 본문으로 보내는 리프레시 토큰. 쿠키 방식(RefreshTokenCookies)에서는 쿠키에 있으므로 비어 있을 수 있다.
 *                     쿠키 방식으로 옮겨 가는 중인 클라이언트는 이전에 저장해 둔 토큰을 한 번 본문으로 보내 쿠키로 바꾼다.
 */
public record RefreshTokenRequest(
        String refreshToken
) {
}

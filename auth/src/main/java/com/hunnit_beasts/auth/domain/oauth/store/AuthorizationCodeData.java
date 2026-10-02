package com.hunnit_beasts.auth.domain.oauth.store;

import java.util.UUID;

/**
 * 인가 코드에 묶이는 인가 결정. 인가 코드 원문은 포함하지 않는다.
 *
 * @param scope     승인된 스코프(공백 구분, 없으면 빈 문자열)
 * @param nonce     인가 요청의 OIDC nonce(없으면 null)
 * @param authTime  사용자가 인증된 시각(epoch seconds)
 */
public record AuthorizationCodeData(
        UUID userId,
        String clientId,
        String redirectUri,
        String codeChallenge,
        String scope,
        String nonce,
        long authTime
) {
}

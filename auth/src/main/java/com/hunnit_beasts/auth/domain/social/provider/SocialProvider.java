package com.hunnit_beasts.auth.domain.social.provider;

import java.net.URI;

/**
 * 소셜 로그인 공급자 하나(GitHub, Google ...). 새 공급자는 이 인터페이스를 구현한 빈을 하나 추가하면 된다
 * (설정에 client-id/secret 을 넣어야 켜진다).
 */
public interface SocialProvider {

    /** URL 에 쓰는 짧은 이름(소문자). 예: github */
    String id();

    /** 화면에 보일 이름. 예: GitHub */
    String displayName();

    /** client-id 와 client-secret 이 모두 설정돼 있으면 true. */
    boolean isConfigured();

    /** 공급자의 로그인(동의) 화면 주소. PKCE(S256)를 쓴다. */
    URI authorizeUri(String redirectUri, String state, String codeChallenge);

    /**
     * 인가 코드를 토큰으로 바꾸고 사용자 정보를 읽는다. 서버가 공급자와 직접(TLS) 주고받으므로 토큰 서명 검증은 필요 없다.
     * @throws SocialLoginException 공급자 응답이 비정상일 때
     */
    SocialProfile fetchProfile(String code, String redirectUri, String codeVerifier);
}

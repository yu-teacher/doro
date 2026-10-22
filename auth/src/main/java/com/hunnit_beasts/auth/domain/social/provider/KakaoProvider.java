package com.hunnit_beasts.auth.domain.social.provider;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * 카카오 로그인. client-id 는 카카오 디벨로퍼스의 "REST API 키", client-secret 은 "보안 → Client Secret" 이다.
 * 이메일은 비즈 앱 전환 후 "카카오계정(이메일)" 동의항목을 켜야 받을 수 있고, 유효(is_email_valid)하고 인증된(is_email_verified)
 * 이메일만 쓴다. 이용자가 이메일 제공에 동의하지 않았거나 인증되지 않았으면 이메일 없음으로 돌려 로그인을 거부한다.
 */
@Component
public class KakaoProvider implements SocialProvider {

    private static final String AUTHORIZE_URL = "https://kauth.kakao.com/oauth/authorize";
    private static final String TOKEN_URL = "https://kauth.kakao.com/oauth/token";
    private static final String USER_URL = "https://kapi.kakao.com/v2/user/me";

    private final String clientId;
    private final String clientSecret;
    private final SocialHttp http;

    @Autowired
    public KakaoProvider(@Value("${doro.social.kakao.client-id:}") String clientId,
                         @Value("${doro.social.kakao.client-secret:}") String clientSecret) {
        this(clientId, clientSecret, new SocialHttp());
    }

    /** 시험에서 공급자 호출을 바꿔 끼우려는 생성자. */
    KakaoProvider(String clientId, String clientSecret, SocialHttp http) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.http = http;
    }

    @Override
    public String id() {
        return "kakao";
    }

    @Override
    public String displayName() {
        return "카카오";
    }

    @Override
    public boolean isConfigured() {
        return !clientId.isBlank() && !clientSecret.isBlank();
    }

    @Override
    public URI authorizeUri(String redirectUri, String state, String codeChallenge) {
        return UriComponentsBuilder.fromUriString(AUTHORIZE_URL)
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", "account_email profile_nickname")
                .queryParam("state", state)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .build()
                .encode()
                .toUri();
    }

    @Override
    public SocialProfile fetchProfile(String code, String redirectUri, String codeVerifier) {
        Map<String, String> form = new HashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        form.put("redirect_uri", redirectUri);
        form.put("code", code);
        form.put("code_verifier", codeVerifier);
        JsonNode token = http.postForm(TOKEN_URL, form);
        String accessToken = token.path("access_token").asText("");
        if (accessToken.isBlank()) {
            throw new SocialLoginException(SocialLoginException.PROVIDER_ERROR, "카카오가 토큰을 주지 않았습니다.");
        }

        JsonNode user = http.getJson(USER_URL, accessToken);
        String id = user.path("id").asText("");
        if (id.isBlank()) {
            throw new SocialLoginException(SocialLoginException.PROVIDER_ERROR, "카카오 사용자 식별자가 없습니다.");
        }

        JsonNode account = user.path("kakao_account");
        String email = account.path("email").asText("");
        boolean usable = !email.isBlank()
                && account.path("is_email_valid").asBoolean(false)
                && account.path("is_email_verified").asBoolean(false);

        String name = account.path("profile").path("nickname").asText("");
        if (name.isBlank()) {
            name = user.path("properties").path("nickname").asText("");
        }
        return new SocialProfile(id, usable ? email : null, usable, name.isBlank() ? null : name);
    }
}

package com.hunnit_beasts.auth.domain.social.provider;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

@Component
public class GoogleProvider implements SocialProvider {

    private static final String AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String USERINFO_URL = "https://openidconnect.googleapis.com/v1/userinfo";

    private final String clientId;
    private final String clientSecret;
    private final SocialHttp http = new SocialHttp();

    public GoogleProvider(@Value("${doro.social.google.client-id:}") String clientId,
                          @Value("${doro.social.google.client-secret:}") String clientSecret) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public String id() {
        return "google";
    }

    @Override
    public String displayName() {
        return "Google";
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
                .queryParam("scope", "openid email profile")
                .queryParam("state", state)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .queryParam("prompt", "select_account")
                .build()
                .encode()
                .toUri();
    }

    @Override
    public SocialProfile fetchProfile(String code, String redirectUri, String codeVerifier) {
        JsonNode token = http.postForm(TOKEN_URL, Map.of(
                "grant_type", "authorization_code",
                "client_id", clientId,
                "client_secret", clientSecret,
                "code", code,
                "redirect_uri", redirectUri,
                "code_verifier", codeVerifier));
        String accessToken = token.path("access_token").asText("");
        if (accessToken.isBlank()) {
            throw new SocialLoginException(SocialLoginException.PROVIDER_ERROR, "Google 이 토큰을 주지 않았습니다.");
        }

        JsonNode info = http.getJson(USERINFO_URL, accessToken);
        String sub = info.path("sub").asText("");
        if (sub.isBlank()) {
            throw new SocialLoginException(SocialLoginException.PROVIDER_ERROR, "Google 사용자 식별자가 없습니다.");
        }
        String email = info.path("email").asText("");
        // email_verified 는 boolean 이 원칙이지만 문자열 "true" 로 오던 시절이 있어 둘 다 받는다.
        JsonNode verifiedNode = info.path("email_verified");
        boolean verified = verifiedNode.isBoolean() ? verifiedNode.asBoolean() : "true".equalsIgnoreCase(verifiedNode.asText(""));
        String name = info.path("name").asText("");
        return new SocialProfile(sub, email.isBlank() ? null : email, verified && !email.isBlank(), name.isBlank() ? null : name);
    }
}

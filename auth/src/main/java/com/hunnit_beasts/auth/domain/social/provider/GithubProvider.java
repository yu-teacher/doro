package com.hunnit_beasts.auth.domain.social.provider;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

@Component
public class GithubProvider implements SocialProvider {

    private static final String AUTHORIZE_URL = "https://github.com/login/oauth/authorize";
    private static final String TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String USER_URL = "https://api.github.com/user";
    private static final String EMAILS_URL = "https://api.github.com/user/emails";
    private static final String NOREPLY_SUFFIX = "@users.noreply.github.com";

    private final String clientId;
    private final String clientSecret;
    private final SocialHttp http;

    @Autowired
    public GithubProvider(@Value("${doro.social.github.client-id:}") String clientId,
                          @Value("${doro.social.github.client-secret:}") String clientSecret) {
        this(clientId, clientSecret, new SocialHttp());
    }

    /** 시험에서 공급자 호출을 바꿔 끼우려는 생성자. */
    GithubProvider(String clientId, String clientSecret, SocialHttp http) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.http = http;
    }

    @Override
    public String id() {
        return "github";
    }

    @Override
    public String displayName() {
        return "GitHub";
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
                .queryParam("scope", "read:user user:email")
                .queryParam("state", state)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .queryParam("allow_signup", "true")
                .build()
                .encode()
                .toUri();
    }

    @Override
    public SocialProfile fetchProfile(String code, String redirectUri, String codeVerifier) {
        JsonNode token = http.postForm(TOKEN_URL, Map.of(
                "client_id", clientId,
                "client_secret", clientSecret,
                "code", code,
                "redirect_uri", redirectUri,
                "code_verifier", codeVerifier));
        String accessToken = token.path("access_token").asText("");
        if (accessToken.isBlank()) {
            throw new SocialLoginException(SocialLoginException.PROVIDER_ERROR, "GitHub 가 토큰을 주지 않았습니다.");
        }

        JsonNode user = http.getJson(USER_URL, accessToken);
        String id = user.path("id").asText("");
        if (id.isBlank()) {
            throw new SocialLoginException(SocialLoginException.PROVIDER_ERROR, "GitHub 사용자 식별자가 없습니다.");
        }
        String name = textOrNull(user.path("name"));
        if (name == null) {
            name = textOrNull(user.path("login"));
        }

        // /user 의 email 은 공개 설정에 따라 비어 있고 소유 확인 여부도 알려 주지 않는다. 확인된 기본(primary) 이메일만 쓴다.
        String email = null;
        JsonNode emails = http.getJson(EMAILS_URL, accessToken);
        if (emails.isArray()) {
            for (JsonNode entry : emails) {
                String candidate = entry.path("email").asText("");
                if (entry.path("primary").asBoolean(false) && entry.path("verified").asBoolean(false)
                        && !candidate.isBlank() && !candidate.toLowerCase().endsWith(NOREPLY_SUFFIX)) {
                    email = candidate;
                    break;
                }
            }
        }
        return new SocialProfile(id, email, email != null, name);
    }

    private static String textOrNull(JsonNode node) {
        String text = node.asText("");
        return text.isBlank() ? null : text;
    }
}

package com.hunnit_beasts.auth.domain.social.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 공급자별 응답 해석(특히 이메일 소유 확인 판정)을 실제 네트워크 없이 시험한다. 이 판정이 계정 자동 연결의 안전을 지킨다.
 */
class SocialProvidersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 호출한 주소·본문을 기록하고 미리 정한 응답을 돌려주는 가짜 HTTP. */
    private static class StubHttp extends SocialHttp {
        final List<String> calls = new ArrayList<>();
        Map<String, String> lastForm;
        String tokenJson = "{\"access_token\":\"tok\"}";
        String userJson = "{}";
        String emailsJson = "[]";

        @Override
        JsonNode postForm(String url, Map<String, String> form) {
            calls.add("POST " + url);
            lastForm = form;
            return json(tokenJson);
        }

        @Override
        JsonNode getJson(String url, String accessToken) {
            calls.add("GET " + url + " bearer=" + accessToken);
            return json(url.endsWith("/emails") ? emailsJson : userJson);
        }

        private static JsonNode json(String text) {
            try {
                return MAPPER.readTree(text);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static Map<String, String> query(URI uri) {
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().toSingleValueMap();
    }

    // ---- 카카오 ----

    private KakaoProvider kakao(StubHttp http) {
        return new KakaoProvider("rest-key", "secret", http);
    }

    @Test
    @DisplayName("카카오: 유효·인증된 이메일이면 그 이메일로 프로필을 만든다")
    void kakaoVerifiedEmail() {
        StubHttp http = new StubHttp();
        http.userJson = "{\"id\":123456789,\"kakao_account\":{\"has_email\":true,\"email\":\"a@kakao.test\","
                + "\"is_email_valid\":true,\"is_email_verified\":true,\"profile\":{\"nickname\":\"도로\"}}}";

        SocialProfile profile = kakao(http).fetchProfile("code-1", "https://doro.test/cb", "verifier-1");

        assertThat(profile.providerUserId()).isEqualTo("123456789");
        assertThat(profile.email()).isEqualTo("a@kakao.test");
        assertThat(profile.emailVerified()).isTrue();
        assertThat(profile.name()).isEqualTo("도로");
        assertThat(http.lastForm).containsEntry("grant_type", "authorization_code").containsEntry("client_secret", "secret")
                .containsEntry("client_id", "rest-key").containsEntry("code", "code-1").containsEntry("code_verifier", "verifier-1");
        assertThat(http.calls).containsExactly("POST https://kauth.kakao.com/oauth/token", "GET https://kapi.kakao.com/v2/user/me bearer=tok");
    }

    @Test
    @DisplayName("카카오: 이메일이 인증되지 않았거나 유효하지 않거나 동의하지 않아 없으면 이메일 없음으로 돌려 로그인을 거부하게 한다")
    void kakaoUnusableEmail() {
        String[] accounts = {
                "{\"email\":\"a@kakao.test\",\"is_email_valid\":true,\"is_email_verified\":false}",
                "{\"email\":\"a@kakao.test\",\"is_email_valid\":false,\"is_email_verified\":true}",
                "{\"email\":\"a@kakao.test\"}",
                "{\"email_needs_agreement\":true}",
                "{}",
        };
        for (String account : accounts) {
            StubHttp http = new StubHttp();
            http.userJson = "{\"id\":1,\"kakao_account\":" + account + "}";
            SocialProfile profile = kakao(http).fetchProfile("c", "r", "v");
            assertThat(profile.emailVerified()).as(account).isFalse();
            assertThat(profile.email()).as(account).isNull();
        }
    }

    @Test
    @DisplayName("카카오: 사용자 ID 나 토큰이 없으면 공급자 오류")
    void kakaoMissingIdOrToken() {
        StubHttp noId = new StubHttp();
        noId.userJson = "{\"kakao_account\":{\"email\":\"a@kakao.test\",\"is_email_valid\":true,\"is_email_verified\":true}}";
        assertThatThrownBy(() -> kakao(noId).fetchProfile("c", "r", "v"))
                .isInstanceOfSatisfying(SocialLoginException.class, e -> assertThat(e.getCode()).isEqualTo(SocialLoginException.PROVIDER_ERROR));

        StubHttp noToken = new StubHttp();
        noToken.tokenJson = "{\"error\":\"invalid_grant\"}";
        assertThatThrownBy(() -> kakao(noToken).fetchProfile("c", "r", "v"))
                .isInstanceOfSatisfying(SocialLoginException.class, e -> assertThat(e.getCode()).isEqualTo(SocialLoginException.PROVIDER_ERROR));
    }

    @Test
    @DisplayName("카카오: 닉네임은 profile 이 없으면 properties 에서 읽고, 둘 다 없으면 이름 없음")
    void kakaoNicknameFallback() {
        StubHttp http = new StubHttp();
        http.userJson = "{\"id\":1,\"properties\":{\"nickname\":\"옛닉\"},\"kakao_account\":{\"email\":\"a@kakao.test\",\"is_email_valid\":true,\"is_email_verified\":true}}";
        assertThat(kakao(http).fetchProfile("c", "r", "v").name()).isEqualTo("옛닉");

        http.userJson = "{\"id\":1,\"kakao_account\":{\"email\":\"a@kakao.test\",\"is_email_valid\":true,\"is_email_verified\":true}}";
        assertThat(kakao(http).fetchProfile("c", "r", "v").name()).isNull();
    }

    @Test
    @DisplayName("카카오: 인가 주소에 이메일 범위·PKCE·state 가 실린다. 키가 하나라도 없으면 꺼진 공급자")
    void kakaoAuthorizeAndConfigured() {
        Map<String, String> q = query(kakao(new StubHttp()).authorizeUri("https://doro.test/cb", "st", "chal"));
        assertThat(q).containsEntry("client_id", "rest-key").containsEntry("response_type", "code").containsEntry("state", "st")
                .containsEntry("code_challenge", "chal").containsEntry("code_challenge_method", "S256");
        assertThat(q.get("scope")).contains("account_email");

        assertThat(new KakaoProvider("", "secret", new StubHttp()).isConfigured()).isFalse();
        assertThat(new KakaoProvider("key", "", new StubHttp()).isConfigured()).isFalse();
        assertThat(kakao(new StubHttp()).isConfigured()).isTrue();
        assertThat(kakao(new StubHttp()).id()).isEqualTo("kakao");
    }

    // ---- GitHub ----

    @Test
    @DisplayName("GitHub: 확인된 대표(primary) 이메일만 쓰고, 미확인·비대표·noreply 주소는 쓰지 않는다")
    void githubEmailSelection() {
        GithubProvider github = null;
        StubHttp http = new StubHttp();
        http.userJson = "{\"id\":42,\"login\":\"octo\",\"name\":\"Octo Cat\"}";
        github = new GithubProvider("id", "secret", http);

        http.emailsJson = "[{\"email\":\"other@x.test\",\"primary\":false,\"verified\":true},"
                + "{\"email\":\"main@x.test\",\"primary\":true,\"verified\":true}]";
        SocialProfile ok = github.fetchProfile("c", "r", "v");
        assertThat(ok.providerUserId()).isEqualTo("42");
        assertThat(ok.email()).isEqualTo("main@x.test");
        assertThat(ok.emailVerified()).isTrue();
        assertThat(ok.name()).isEqualTo("Octo Cat");

        String[] unusable = {
                "[{\"email\":\"main@x.test\",\"primary\":true,\"verified\":false}]",
                "[{\"email\":\"other@x.test\",\"primary\":false,\"verified\":true}]",
                "[{\"email\":\"42+octo@users.noreply.github.com\",\"primary\":true,\"verified\":true}]",
                "[]",
        };
        for (String emails : unusable) {
            http.emailsJson = emails;
            SocialProfile profile = github.fetchProfile("c", "r", "v");
            assertThat(profile.emailVerified()).as(emails).isFalse();
            assertThat(profile.email()).as(emails).isNull();
        }
    }

    @Test
    @DisplayName("GitHub: 이름이 없으면 login 으로 대신하고, 사용자 ID 가 없으면 공급자 오류")
    void githubNameFallbackAndMissingId() {
        StubHttp http = new StubHttp();
        http.userJson = "{\"id\":7,\"login\":\"octo\",\"name\":null}";
        http.emailsJson = "[{\"email\":\"a@x.test\",\"primary\":true,\"verified\":true}]";
        assertThat(new GithubProvider("id", "secret", http).fetchProfile("c", "r", "v").name()).isEqualTo("octo");

        http.userJson = "{\"login\":\"octo\"}";
        assertThatThrownBy(() -> new GithubProvider("id", "secret", http).fetchProfile("c", "r", "v"))
                .isInstanceOf(SocialLoginException.class);
    }

    // ---- Google ----

    @Test
    @DisplayName("Google: email_verified 가 true(불리언 또는 문자열 \"true\")일 때만 이메일을 인정한다")
    void googleEmailVerified() {
        StubHttp http = new StubHttp();
        GoogleProvider google = new GoogleProvider("id", "secret", http);

        http.userJson = "{\"sub\":\"g1\",\"email\":\"a@g.test\",\"email_verified\":true,\"name\":\"Gee\"}";
        SocialProfile boolOk = google.fetchProfile("c", "r", "v");
        assertThat(boolOk.emailVerified()).isTrue();
        assertThat(boolOk.email()).isEqualTo("a@g.test");

        http.userJson = "{\"sub\":\"g1\",\"email\":\"a@g.test\",\"email_verified\":\"true\"}";
        assertThat(google.fetchProfile("c", "r", "v").emailVerified()).isTrue();

        for (String bad : new String[]{
                "{\"sub\":\"g1\",\"email\":\"a@g.test\",\"email_verified\":false}",
                "{\"sub\":\"g1\",\"email\":\"a@g.test\"}",
                "{\"sub\":\"g1\",\"email_verified\":true}",
        }) {
            http.userJson = bad;
            SocialProfile profile = google.fetchProfile("c", "r", "v");
            assertThat(profile.emailVerified()).as(bad).isFalse();
        }

        http.userJson = "{\"email\":\"a@g.test\",\"email_verified\":true}";
        assertThatThrownBy(() -> google.fetchProfile("c", "r", "v")).isInstanceOf(SocialLoginException.class);
    }
}

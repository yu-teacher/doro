package com.hunnit_beasts.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunnit_beasts.auth.domain.credential.repository.CredentialRepository;
import com.hunnit_beasts.auth.domain.social.provider.SocialLoginException;
import com.hunnit_beasts.auth.domain.social.provider.SocialProfile;
import com.hunnit_beasts.auth.domain.social.provider.SocialProvider;
import com.hunnit_beasts.auth.domain.social.repository.SocialIdentityRepository;
import com.hunnit_beasts.auth.domain.user.entity.User;
import com.hunnit_beasts.auth.domain.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 소셜 로그인: 가짜 공급자로 시작→콜백 전체 흐름을 검증한다(실제 GitHub/Google 호출은 하지 않는다).
 * 핵심은 계정 연결 정책이다: 이메일 소유가 확인된 경우에만 가입·연결하고, 확인된 적 없는 기존 비밀번호 계정에 연결할 때는
 * 예전 비밀번호·2FA·세션을 버린다.
 */
@SpringBootTest(properties = "doro.social.public-base-url=https://doro.test")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SocialLoginTest.FakeProviderConfig.class)
class SocialLoginTest {

    private static final String PASSWORD = "Password123!";
    private static final String STATE_COOKIE = "doro_sso_state";
    /** 다음 콜백에서 가짜 공급자가 돌려줄 사용자. */
    private static final AtomicReference<SocialProfile> NEXT_PROFILE = new AtomicReference<>();

    @TestConfiguration
    static class FakeProviderConfig {
        @Bean
        SocialProvider fakeProvider() {
            return fake("fake");
        }

        @Bean
        SocialProvider fakeProvider2() {
            return fake("fake2");
        }

        private static SocialProvider fake(String id) {
            return new SocialProvider() {
                @Override public String id() { return id; }
                @Override public String displayName() { return id; }
                @Override public boolean isConfigured() { return true; }
                @Override public URI authorizeUri(String redirectUri, String state, String codeChallenge) {
                    return UriComponentsBuilder.fromUriString("https://" + id + ".example/authorize")
                            .queryParam("redirect_uri", redirectUri).queryParam("state", state)
                            .queryParam("code_challenge", codeChallenge).build().toUri();
                }
                @Override public SocialProfile fetchProfile(String code, String redirectUri, String codeVerifier) {
                    if ("boom".equals(code)) {
                        throw new SocialLoginException(SocialLoginException.PROVIDER_ERROR, "boom");
                    }
                    return NEXT_PROFILE.get();
                }
            };
        }
    }

    /** Guard(권한 서버)는 부르지 않는다. 가입 시 튜플 동기화와 탈퇴 파기의 튜플 삭제가 여기로 간다. */
    @MockitoBean private com.hunnit_beasts.auth.infrastructure.guard.GuardClient guardClient;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private CredentialRepository credentialRepository;
    @Autowired private SocialIdentityRepository identityRepository;
    @Autowired private com.hunnit_beasts.auth.domain.social.service.SocialLoginService socialLoginService;
    @Autowired private com.hunnit_beasts.auth.domain.user.service.AccountPurgeService purgeService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String email;
    private String providerId;

    @BeforeEach
    void setUp() {
        email = "social-" + UUID.randomUUID() + "@doro.local";
        providerId = "fake-" + UUID.randomUUID();
        NEXT_PROFILE.set(new SocialProfile(providerId, email, true, "Social Tester"));
    }

    /** start 를 호출해 state 와 쿠키를 얻는다. */
    private record Started(String state, Cookie cookie) {}

    private Started start(int slot) throws Exception {
        return start("fake", slot);
    }

    private Started start(String provider, int slot) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/api/v1/auth/social/" + provider + "/start").param("slot", String.valueOf(slot)))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(302);
        String location = response.getHeader("Location");
        assertThat(location).startsWith("https://" + provider + ".example/authorize");
        assertThat(location).contains("redirect_uri=https://doro.test/api/v1/auth/social/" + provider + "/callback");
        String state = UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("state");
        String setCookie = response.getHeader("Set-Cookie");
        assertThat(setCookie).contains(STATE_COOKIE + "=" + state).contains("HttpOnly").contains("SameSite=Lax")
                .contains("Path=/api/v1/auth/social");
        return new Started(state, new Cookie(STATE_COOKIE, state));
    }

    private MockHttpServletResponse callback(String state, Cookie cookie, String code) throws Exception {
        var request = get("/api/v1/auth/social/fake/callback").param("code", code).param("state", state);
        if (cookie != null) {
            request.cookie(cookie);
        }
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private MockHttpServletResponse login(int slot) throws Exception {
        Started started = start(slot);
        return callback(started.state(), started.cookie(), "code-ok");
    }

    private MockHttpServletResponse loginWith(String provider, int slot) throws Exception {
        Started started = start(provider, slot);
        var request = get("/api/v1/auth/social/" + provider + "/callback").param("code", "code-ok").param("state", started.state())
                .cookie(started.cookie());
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private static String loginLocation(MockHttpServletResponse response) {
        assertThat(response.getStatus()).isEqualTo(302);
        return response.getHeader("Location");
    }

    private static String refreshCookieOf(MockHttpServletResponse response, int slot) {
        return response.getHeaders("Set-Cookie").stream().filter(h -> h.startsWith("doro_rt_" + slot + "="))
                .map(h -> h.substring(h.indexOf('=') + 1, h.indexOf(';'))).findFirst().orElse(null);
    }

    private JsonNode refresh(int slot, String token) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(post("/api/v1/auth/token/refresh").contentType(MediaType.APPLICATION_JSON)
                .cookie(new Cookie("doro_rt_" + slot, token)).header("X-Doro-Cookie-Session", "1").header("X-Doro-Account-Slot", String.valueOf(slot)))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return objectMapper.readTree(response.getContentAsString()).path("data");
    }

    @Test
    @DisplayName("공급자 목록: 설정이 끝난 공급자만 나온다(GitHub·Google 은 키가 없어 꺼져 있다)")
    void listsOnlyConfiguredProviders() throws Exception {
        String body = mockMvc.perform(get("/api/v1/auth/social/providers")).andReturn().getResponse().getContentAsString();
        List<String> ids = objectMapper.readTree(body).path("data").findValuesAsText("id");
        assertThat(ids).contains("fake").doesNotContain("github", "google");
    }

    @Test
    @DisplayName("새 이메일이면 비밀번호 없는 계정을 만들고, 리프레시 쿠키(슬롯)를 심고 /login?social=ok 로 돌려보낸다")
    void createsNewAccountAndIssuesRefreshCookie() throws Exception {
        MockHttpServletResponse response = login(2);

        assertThat(loginLocation(response)).isEqualTo("/login?social=ok&slot=2");
        String refreshToken = refreshCookieOf(response, 2);
        assertThat(refreshToken).isNotBlank();
        assertThat(response.getHeader("Cache-Control")).contains("no-store");

        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(user.isEmailVerified()).isTrue();
        assertThat(user.getName()).isEqualTo("Social Tester");
        assertThat(credentialRepository.findByUserId(user.getId()).orElseThrow().isPasswordSet()).isFalse();
        assertThat(identityRepository.findByProviderAndProviderUserId("fake", providerId)).isPresent();

        // 화면은 이 쿠키로 액세스 토큰을 받아 간다
        assertThat(refresh(2, refreshToken).path("accessToken").asText()).isNotBlank();
    }

    @Test
    @DisplayName("같은 외부 계정으로 다시 들어오면 새 계정을 만들지 않는다")
    void secondLoginReusesTheSameAccount() throws Exception {
        login(0);
        login(1);
        assertThat(userRepository.findAllByEmailIgnoreCase(email)).hasSize(1);
    }

    @Test
    @DisplayName("연결된 뒤에는 공급자 쪽 이메일이 바뀌어도 같은 계정으로 들어온다")
    void linkedIdentityWinsOverEmail() throws Exception {
        login(0);
        UUID userId = userRepository.findByEmailIgnoreCase(email).orElseThrow().getId();

        NEXT_PROFILE.set(new SocialProfile(providerId, "changed-" + email, true, "Social Tester"));
        MockHttpServletResponse response = login(0);

        assertThat(loginLocation(response)).startsWith("/login?social=ok");
        assertThat(userRepository.findByEmailIgnoreCase("changed-" + email)).isEmpty();
        assertThat(identityRepository.findByProviderAndProviderUserId("fake", providerId).orElseThrow().getUserId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("같은 이메일의 비밀번호 계정에 자동 연결하되, 확인된 적 없는 계정이므로 예전 비밀번호·세션은 버린다")
    void autoLinkDiscardsUnverifiedPasswordAccount() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"name\":\"Original\"}"));
        MockHttpServletResponse before = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .header("X-Doro-Cookie-Session", "1").header("X-Doro-Account-Slot", "3")
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")).andReturn().getResponse();
        String oldRefresh = refreshCookieOf(before, 3);
        assertThat(oldRefresh).isNotBlank();
        UUID userId = userRepository.findByEmailIgnoreCase(email).orElseThrow().getId();
        assertThat(userRepository.findById(userId).orElseThrow().isEmailVerified()).isFalse();

        MockHttpServletResponse response = login(1);

        assertThat(loginLocation(response)).isEqualTo("/login?social=ok&slot=1");
        // 같은 계정에 연결됐다(새 계정이 생기지 않았다)
        assertThat(userRepository.findAllByEmailIgnoreCase(email)).hasSize(1);
        assertThat(identityRepository.findByProviderAndProviderUserId("fake", providerId).orElseThrow().getUserId()).isEqualTo(userId);
        assertThat(userRepository.findById(userId).orElseThrow().isEmailVerified()).isTrue();
        // 이름은 그대로 둔다(공급자 값으로 덮어쓰지 않는다)
        assertThat(userRepository.findById(userId).orElseThrow().getName()).isEqualTo("Original");

        // 예전 비밀번호는 더 이상 통하지 않고, 예전 세션(리프레시 토큰)도 끝났다
        int loginStatus = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")).andReturn().getResponse().getStatus();
        assertThat(loginStatus).isEqualTo(401);
        int refreshStatus = mockMvc.perform(post("/api/v1/auth/token/refresh").contentType(MediaType.APPLICATION_JSON)
                .cookie(new Cookie("doro_rt_3", oldRefresh)).header("X-Doro-Cookie-Session", "1").header("X-Doro-Account-Slot", "3"))
                .andReturn().getResponse().getStatus();
        assertThat(refreshStatus).isGreaterThanOrEqualTo(400);
        assertThat(credentialRepository.findByUserId(userId).orElseThrow().isPasswordSet()).isFalse();
    }

    @Test
    @DisplayName("이미 소셜로 확인된 계정에 다른 공급자를 더 연결해도 비밀번호는 건드리지 않는다")
    void secondProviderOnVerifiedAccountKeepsPassword() throws Exception {
        login(0);
        UUID userId = userRepository.findByEmailIgnoreCase(email).orElseThrow().getId();
        // 비밀번호를 정한다(현재 비밀번호 없이 처음 설정)
        String access = refresh(0, refreshCookieOf(login(0), 0)).path("accessToken").asText();
        int set = mockMvc.perform(put("/api/v1/users/me/password").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + access).content("{\"newPassword\":\"" + PASSWORD + "\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(set).isEqualTo(200);
        assertThat(credentialRepository.findByUserId(userId).orElseThrow().isPasswordSet()).isTrue();

        // 다른 공급자(fake2)로 같은 이메일을 확인해 들어온다: 같은 계정에 연결되고 비밀번호·세션은 그대로다
        NEXT_PROFILE.set(new SocialProfile("second-" + providerId, email, true, "Social Tester"));
        MockHttpServletResponse response = loginWith("fake2", 1);
        assertThat(loginLocation(response)).isEqualTo("/login?social=ok&slot=1");
        assertThat(userRepository.findAllByEmailIgnoreCase(email)).hasSize(1);
        assertThat(identityRepository.findByUserIdAndProvider(userId, "fake2")).isPresent();
        assertThat(identityRepository.findByUserIdAndProvider(userId, "fake")).isPresent();
        assertThat(credentialRepository.findByUserId(userId).orElseThrow().isPasswordSet()).isTrue();
        // 기존 세션도 끝나지 않았다(이미 확인된 계정이라 버릴 것이 없다)
        assertThat(refresh(0, refreshCookieOf(loginWith("fake", 0), 0)).path("accessToken").asText()).isNotBlank();
        int loginStatus = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")).andReturn().getResponse().getStatus();
        assertThat(loginStatus).isEqualTo(200);
    }

    @Test
    @DisplayName("공급자가 이메일 소유를 확인해 주지 않으면 계정을 만들지도 연결하지도 않는다")
    void unverifiedEmailIsRejected() throws Exception {
        NEXT_PROFILE.set(new SocialProfile(providerId, email, false, "Nobody"));
        MockHttpServletResponse response = login(0);

        assertThat(loginLocation(response)).isEqualTo("/login?social=error&code=email_not_verified");
        assertThat(userRepository.findByEmailIgnoreCase(email)).isEmpty();
        assertThat(refreshCookieOf(response, 0)).isNull();

        NEXT_PROFILE.set(new SocialProfile(providerId, null, false, "Nobody"));
        assertThat(loginLocation(login(0))).isEqualTo("/login?social=error&code=email_not_verified");
    }

    @Test
    @DisplayName("state 가 이 브라우저의 쿠키와 다르거나 쿠키가 없으면 거부한다(로그인 CSRF)")
    void stateMustMatchBrowserCookie() throws Exception {
        Started started = start(0);

        assertThat(loginLocation(callback(started.state(), null, "code-ok"))).isEqualTo("/login?social=error&code=invalid_state");
        assertThat(loginLocation(callback(started.state(), new Cookie(STATE_COOKIE, "someone-else"), "code-ok")))
                .isEqualTo("/login?social=error&code=invalid_state");
        assertThat(userRepository.findByEmailIgnoreCase(email)).isEmpty();
    }

    @Test
    @DisplayName("state 는 한 번만 쓸 수 있다(재전송 거부)")
    void stateIsSingleUse() throws Exception {
        Started started = start(0);
        assertThat(loginLocation(callback(started.state(), started.cookie(), "code-ok"))).startsWith("/login?social=ok");
        assertThat(loginLocation(callback(started.state(), started.cookie(), "code-ok"))).isEqualTo("/login?social=error&code=invalid_state");
    }

    @Test
    @DisplayName("공급자 호출이 실패하면 provider_error 로 돌려보내고 상태 쿠키는 지운다")
    void providerFailureRedirectsWithError() throws Exception {
        Started started = start(0);
        MockHttpServletResponse response = callback(started.state(), started.cookie(), "boom");
        assertThat(loginLocation(response)).isEqualTo("/login?social=error&code=provider_error");
        assertThat(response.getHeaders("Set-Cookie").stream().anyMatch(h -> h.startsWith(STATE_COOKIE + "=;") || h.contains("Max-Age=0"))).isTrue();
    }

    @Test
    @DisplayName("공급자 화면에서 취소하면(access_denied) denied 로 돌려보낸다")
    void userCancelRedirectsAsDenied() throws Exception {
        Started started = start(0);
        MockHttpServletResponse response = mockMvc.perform(get("/api/v1/auth/social/fake/callback")
                .param("error", "access_denied").param("state", started.state()).cookie(started.cookie())).andReturn().getResponse();
        assertThat(loginLocation(response)).isEqualTo("/login?social=error&code=denied");
    }

    @Test
    @DisplayName("알 수 없는 공급자·잘못된 슬롯은 로그인 화면으로 오류와 함께 돌려보낸다")
    void badStartRequests() throws Exception {
        assertThat(loginLocation(mockMvc.perform(get("/api/v1/auth/social/nope/start").param("slot", "0")).andReturn().getResponse()))
                .isEqualTo("/login?social=error&code=unavailable");
        assertThat(loginLocation(mockMvc.perform(get("/api/v1/auth/social/fake/start").param("slot", "9")).andReturn().getResponse()))
                .isEqualTo("/login?social=error&code=provider_error");
        assertThat(loginLocation(mockMvc.perform(get("/api/v1/auth/social/fake/start")).andReturn().getResponse()))
                .isEqualTo("/login?social=error&code=provider_error");
    }

    @Test
    @DisplayName("정지된 계정은 소셜로도 들어올 수 없다")
    void suspendedAccountIsRejected() throws Exception {
        login(0);
        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        user.suspend(java.time.Instant.now(), UUID.randomUUID(), "test");
        userRepository.saveAndFlush(user);

        MockHttpServletResponse response = login(0);
        assertThat(loginLocation(response)).isEqualTo("/login?social=error&code=account_suspended");
        assertThat(refreshCookieOf(response, 0)).isNull();
    }

    @Test
    @DisplayName("2FA 를 켠 계정은 소셜로 들어와도 세션 대신 2FA 티켓을 받는다")
    void twoFactorAccountGetsTicketInsteadOfSession() throws Exception {
        login(0);
        UUID userId = userRepository.findByEmailIgnoreCase(email).orElseThrow().getId();
        var credential = credentialRepository.findByUserId(userId).orElseThrow();
        credential.updateTotpSecret("JBSWY3DPEHPK3PXP");
        credentialRepository.saveAndFlush(credential);

        MockHttpServletResponse response = login(4);
        String location = loginLocation(response);
        assertThat(location).startsWith("/login?social=2fa&").contains("ticket=").contains("slot=4");
        assertThat(refreshCookieOf(response, 4)).isNull();
    }

    @Test
    @DisplayName("비밀번호 없는 소셜 계정은 현재 비밀번호 없이 처음 비밀번호를 정하고, 그 뒤에는 현재 비밀번호를 요구한다")
    void socialOnlyAccountCanSetFirstPassword() throws Exception {
        String access = refresh(0, refreshCookieOf(login(0), 0)).path("accessToken").asText();

        JsonNode me = objectMapper.readTree(mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + access))
                .andReturn().getResponse().getContentAsString()).path("data");
        assertThat(me.path("passwordSet").asBoolean(true)).isFalse();

        int first = mockMvc.perform(put("/api/v1/users/me/password").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + access).content("{\"newPassword\":\"" + PASSWORD + "\"}")).andReturn().getResponse().getStatus();
        assertThat(first).isEqualTo(200);

        // 이제는 비밀번호가 있으니 현재 비밀번호 없이는 바꿀 수 없다
        int second = mockMvc.perform(put("/api/v1/users/me/password").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + access).content("{\"newPassword\":\"Another123!\"}")).andReturn().getResponse().getStatus();
        assertThat(second).isEqualTo(400);

        int passwordLogin = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")).andReturn().getResponse().getStatus();
        assertThat(passwordLogin).isEqualTo(200);
    }

    @Test
    @DisplayName("탈퇴 유예가 끝나 익명화되면 연결된 소셜 계정도 지워지고, 같은 외부 계정으로 새로 가입할 수 있다")
    void purgeRemovesSocialIdentities() throws Exception {
        login(0);
        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        user.requestDeletion(java.time.Instant.now().minus(java.time.Duration.ofDays(40)));
        userRepository.saveAndFlush(user);

        assertThat(purgeService.purgeDueAccounts(java.time.Instant.now())).isGreaterThanOrEqualTo(1);

        assertThat(identityRepository.findByProviderAndProviderUserId("fake", providerId)).isEmpty();
        // 이메일도 익명화돼 비었으니 같은 외부 계정으로 다시 가입할 수 있다(이전 데이터는 이어지지 않는다)
        assertThat(loginLocation(login(0))).startsWith("/login?social=ok");
        assertThat(userRepository.findByEmailIgnoreCase(email).orElseThrow().getId()).isNotEqualTo(user.getId());
    }

    @Test
    @DisplayName("공급자 화면에서 돌아오지 않은 로그인 시작 상태는 시간이 지나면 정리된다(메모리 누적 방지)")
    void expiredPendingLoginsArePurged() throws Exception {
        start(0);
        int before = socialLoginService.pendingCount();
        assertThat(before).isGreaterThanOrEqualTo(1);

        assertThat(socialLoginService.purgeExpired(java.time.Instant.now().plusSeconds(601))).isGreaterThanOrEqualTo(1);
        assertThat(socialLoginService.pendingCount()).isLessThan(before);
    }

    @Test
    @DisplayName("비밀번호 없는 소셜 계정은 비밀번호 없이 탈퇴를 요청할 수 있고(30일 유예), 비밀번호가 있는 계정은 여전히 비밀번호를 요구한다")
    void deletionWithoutPasswordOnlyForPasswordlessAccounts() throws Exception {
        String access = refresh(0, refreshCookieOf(login(0), 0)).path("accessToken").asText();

        // 비밀번호를 정하면 다시 비밀번호가 필요하다
        mockMvc.perform(put("/api/v1/users/me/password").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + access).content("{\"newPassword\":\"" + PASSWORD + "\"}"));
        int missing = mockMvc.perform(post("/api/v1/users/me/deletion").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + access).content("{}")).andReturn().getResponse().getStatus();
        assertThat(missing).isEqualTo(400);
        assertThat(userRepository.findByEmailIgnoreCase(email).orElseThrow().isActive()).isTrue();
    }

    @Test
    @DisplayName("비밀번호를 정한 적 없는 소셜 계정은 비밀번호 없이 탈퇴를 접수하고, 유예 중 소셜로 다시 들어오면 복구된다")
    void passwordlessAccountCanRequestDeletionAndRestore() throws Exception {
        String access = refresh(0, refreshCookieOf(login(0), 0)).path("accessToken").asText();

        int status = mockMvc.perform(post("/api/v1/users/me/deletion").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + access).content("{}")).andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(200);
        assertThat(userRepository.findByEmailIgnoreCase(email).orElseThrow().isPendingDeletion()).isTrue();

        assertThat(loginLocation(login(0))).startsWith("/login?social=ok");
        assertThat(userRepository.findByEmailIgnoreCase(email).orElseThrow().isActive()).isTrue();
    }
}

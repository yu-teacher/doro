package com.hunnit_beasts.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import jakarta.servlet.http.Cookie;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 포털용 HttpOnly 리프레시 토큰 쿠키: 쿠키 방식을 켠 요청에서만 쿠키를 쓰고, 토큰은 본문에서 빠지며,
 * 쿠키로 온 토큰은 커스텀 헤더 없이는 받지 않는다. 헤더를 안 보내는 기존 클라이언트는 그대로 동작한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RefreshTokenCookieTest {

    private static final String PASSWORD = "Password123!";
    private static final String COOKIE_HEADER = "X-Doro-Cookie-Session";
    private static final String SLOT_HEADER = "X-Doro-Account-Slot";

    @Autowired private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String newEmail() throws Exception {
        String email = "cookie-" + UUID.randomUUID() + "@doro.local";
        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"name\":\"Cookie\"}"));
        return email;
    }

    private MockHttpServletRequestBuilder loginRequest(String email) {
        return post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}");
    }

    private static String setCookieOf(MockHttpServletResponse response, int slot) {
        return response.getHeaders("Set-Cookie").stream().filter(h -> h.startsWith("doro_rt_" + slot + "=")).findFirst().orElse(null);
    }

    private static String valueOf(String setCookie) {
        return setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
    }

    private JsonNode data(MockHttpServletResponse response) throws Exception {
        return objectMapper.readTree(response.getContentAsString()).path("data");
    }

    private MockHttpServletRequestBuilder refreshWithCookie(int slot, String token, boolean withHeader) {
        MockHttpServletRequestBuilder request = post("/api/v1/auth/token/refresh").contentType(MediaType.APPLICATION_JSON)
                .cookie(new Cookie("doro_rt_" + slot, token));
        if (withHeader) {
            request.header(COOKIE_HEADER, "1").header(SLOT_HEADER, String.valueOf(slot));
        }
        return request;
    }

    @Test
    @DisplayName("쿠키 방식 로그인: 리프레시 토큰은 HttpOnly·Secure·SameSite=Strict·인증 경로 한정 쿠키로만 오고 본문에는 없다")
    void loginSetsHttpOnlyCookieAndHidesTokenFromBody() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(loginRequest(newEmail()).header(COOKIE_HEADER, "1").header(SLOT_HEADER, "2"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        String cookie = setCookieOf(response, 2);
        assertThat(cookie).isNotNull();
        assertThat(cookie).contains("HttpOnly").contains("Secure").contains("SameSite=Strict").contains("Path=/api/v1/auth");
        assertThat(cookie).containsPattern("Max-Age=\\d{6,}");
        assertThat(valueOf(cookie)).isNotBlank();
        JsonNode tokens = data(response).path("tokens");
        assertThat(tokens.path("accessToken").asText()).isNotBlank();
        assertThat(tokens.path("refreshToken").isNull() || tokens.path("refreshToken").isMissingNode()).isTrue();
        assertThat(response.getContentAsString()).doesNotContain(valueOf(cookie));
    }

    @Test
    @DisplayName("헤더를 보내지 않는 기존 방식은 그대로: 본문에 토큰이 있고 쿠키는 없다")
    void legacyModeUnchanged() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(loginRequest(newEmail())).andReturn().getResponse();

        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
        assertThat(data(response).path("tokens").path("refreshToken").asText()).isNotBlank();
    }

    @Test
    @DisplayName("쿠키로 갱신하면 새 액세스 토큰을 받고 쿠키가 회전하며, 이전 쿠키 값은 더 못 쓴다")
    void refreshRotatesCookie() throws Exception {
        MockHttpServletResponse login = mockMvc.perform(loginRequest(newEmail()).header(COOKIE_HEADER, "1").header(SLOT_HEADER, "0"))
                .andReturn().getResponse();
        String first = valueOf(setCookieOf(login, 0));

        MockHttpServletResponse refreshed = mockMvc.perform(refreshWithCookie(0, first, true)).andReturn().getResponse();

        assertThat(refreshed.getStatus()).isEqualTo(200);
        assertThat(data(refreshed).path("accessToken").asText()).isNotBlank();
        String second = valueOf(setCookieOf(refreshed, 0));
        assertThat(second).isNotEqualTo(first);
        assertThat(refreshed.getContentAsString()).doesNotContain(second);
        MockHttpServletResponse reused = mockMvc.perform(refreshWithCookie(0, first, true)).andReturn().getResponse();
        assertThat(reused.getStatus()).isGreaterThanOrEqualTo(400);
    }

    @Test
    @DisplayName("쿠키로 온 토큰은 커스텀 헤더가 없으면 받지 않는다(다른 사이트의 요청으로 회전시킬 수 없다)")
    void cookieWithoutHeaderIsRejected() throws Exception {
        MockHttpServletResponse login = mockMvc.perform(loginRequest(newEmail()).header(COOKIE_HEADER, "1").header(SLOT_HEADER, "0"))
                .andReturn().getResponse();
        String token = valueOf(setCookieOf(login, 0));

        MockHttpServletResponse response = mockMvc.perform(refreshWithCookie(0, token, false)).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
        // 거부된 요청이 토큰을 소모하지 않았으므로 올바른 요청은 여전히 된다
        assertThat(mockMvc.perform(refreshWithCookie(0, token, true)).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("계정마다 슬롯이 따로라 한 계정의 쿠키로 다른 계정의 슬롯을 갱신할 수 없고, 슬롯 범위를 벗어나면 400")
    void slotsAreIndependentAndValidated() throws Exception {
        MockHttpServletResponse a = mockMvc.perform(loginRequest(newEmail()).header(COOKIE_HEADER, "1").header(SLOT_HEADER, "0")).andReturn().getResponse();
        MockHttpServletResponse b = mockMvc.perform(loginRequest(newEmail()).header(COOKIE_HEADER, "1").header(SLOT_HEADER, "1")).andReturn().getResponse();
        String tokenA = valueOf(setCookieOf(a, 0));
        String tokenB = valueOf(setCookieOf(b, 1));
        assertThat(tokenA).isNotEqualTo(tokenB);

        // 슬롯 1 요청인데 쿠키는 슬롯 0 것만 있다 → 토큰 없음
        MockHttpServletRequestBuilder crossed = post("/api/v1/auth/token/refresh").contentType(MediaType.APPLICATION_JSON)
                .cookie(new Cookie("doro_rt_0", tokenA)).header(COOKIE_HEADER, "1").header(SLOT_HEADER, "1");
        assertThat(mockMvc.perform(crossed).andReturn().getResponse().getStatus()).isEqualTo(400);

        for (String bad : new String[]{"5", "-1", "x", ""}) {
            // 슬롯 오류는 로그인(세션 생성) 전에 거른다
            String email = newEmail();
            assertThat(mockMvc.perform(loginRequest(email).header(COOKIE_HEADER, "1").header(SLOT_HEADER, bad)).andReturn().getResponse().getStatus())
                    .as("slot=" + bad).isEqualTo(400);
        }
    }

    @Test
    @DisplayName("본문의 이전 토큰을 한 번 보내 쿠키 방식으로 옮겨 갈 수 있다(기존 로그인 유지)")
    void legacyTokenMigratesToCookie() throws Exception {
        MockHttpServletResponse login = mockMvc.perform(loginRequest(newEmail())).andReturn().getResponse();
        String legacyToken = data(login).path("tokens").path("refreshToken").asText();

        MockHttpServletResponse migrated = mockMvc.perform(post("/api/v1/auth/token/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + legacyToken + "\"}").header(COOKIE_HEADER, "1").header(SLOT_HEADER, "3")).andReturn().getResponse();

        assertThat(migrated.getStatus()).isEqualTo(200);
        assertThat(setCookieOf(migrated, 3)).contains("HttpOnly");
        assertThat(data(migrated).path("refreshToken").isNull() || data(migrated).path("refreshToken").isMissingNode()).isTrue();
    }

    @Test
    @DisplayName("서버가 토큰을 거부하면 낡은 쿠키를 지운다(Max-Age=0), 토큰이 아예 없으면 400")
    void rejectedRefreshClearsCookie() throws Exception {
        MockHttpServletResponse rejected = mockMvc.perform(refreshWithCookie(1, "not-a-real-token", true)).andReturn().getResponse();
        assertThat(rejected.getStatus()).isGreaterThanOrEqualTo(400);
        assertThat(setCookieOf(rejected, 1)).contains("Max-Age=0");

        MockHttpServletResponse missing = mockMvc.perform(post("/api/v1/auth/token/refresh").contentType(MediaType.APPLICATION_JSON)
                .header(COOKIE_HEADER, "1").header(SLOT_HEADER, "1")).andReturn().getResponse();
        assertThat(missing.getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("로그아웃하면 그 슬롯의 쿠키를 지운다")
    void logoutClearsCookie() throws Exception {
        MockHttpServletResponse login = mockMvc.perform(loginRequest(newEmail()).header(COOKIE_HEADER, "1").header(SLOT_HEADER, "4")).andReturn().getResponse();
        JsonNode tokens = data(login).path("tokens");

        MockHttpServletResponse logout = mockMvc.perform(post("/api/v1/auth/logout").param("sessionId", tokens.path("sessionId").asText())
                .header("Authorization", "Bearer " + tokens.path("accessToken").asText()).header(COOKIE_HEADER, "1").header(SLOT_HEADER, "4"))
                .andReturn().getResponse();

        assertThat(logout.getStatus()).isEqualTo(200);
        assertThat(setCookieOf(logout, 4)).contains("Max-Age=0");
    }
}

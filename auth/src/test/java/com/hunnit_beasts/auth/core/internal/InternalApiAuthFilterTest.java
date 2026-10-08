package com.hunnit_beasts.auth.core.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** 필터를 서버 없이 직접 돌려, 모드·경로·헤더 조합마다 통과/거부가 어떻게 되는지 검증한다. */
class InternalApiAuthFilterTest {

    private static final String TOKEN = "t".repeat(40);

    private static InternalApiAuthFilter filter(String mode, String tokens) {
        return new InternalApiAuthFilter(mode, tokens);
    }

    private static MockHttpServletRequest get(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        return request;
    }

    /** 필터를 통과해 다음 체인까지 갔는지(true) 막혔는지(false) */
    private static boolean reachesController(InternalApiAuthFilter filter, MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return chain.getRequest() != null;
    }

    @Test
    @DisplayName("ENFORCE: 토큰이 없거나 틀리면 401(표준 오류 본문), 맞으면 통과")
    void enforce() throws Exception {
        InternalApiAuthFilter filter = filter("ENFORCE", "blog:" + TOKEN);

        MockHttpServletResponse missing = new MockHttpServletResponse();
        assertThat(reachesController(filter, get("/internal/v1/deleted-users"), missing)).isFalse();
        assertThat(missing.getStatus()).isEqualTo(401);
        assertThat(missing.getContentAsString()).contains("\"success\":false").contains("AUTH_40101").doesNotContain(TOKEN);

        MockHttpServletRequest wrong = get("/internal/v1/deleted-users");
        wrong.addHeader("X-Doro-Service-Token", "w".repeat(40));
        MockHttpServletResponse wrongResponse = new MockHttpServletResponse();
        assertThat(reachesController(filter, wrong, wrongResponse)).isFalse();
        assertThat(wrongResponse.getStatus()).isEqualTo(401);

        MockHttpServletRequest right = get("/internal/v1/deleted-users");
        right.addHeader("X-Doro-Service-Token", TOKEN);
        MockHttpServletResponse ok = new MockHttpServletResponse();
        assertThat(reachesController(filter, right, ok)).isTrue();
        assertThat(ok.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("ENFORCE 인데 호출자 토큰을 하나도 설정하지 않았으면 어떤 요청도 통과하지 못한다(빈 토큰 포함)")
    void enforceWithoutConfiguredTokensRejectsEverything() throws Exception {
        InternalApiAuthFilter filter = filter("ENFORCE", "");
        for (String header : new String[]{null, "", TOKEN}) {
            MockHttpServletRequest request = get("/internal/v1/deleted-users");
            if (header != null) {
                request.addHeader("X-Doro-Service-Token", header);
            }
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertThat(reachesController(filter, request, response)).as("header=%s", header).isFalse();
            assertThat(response.getStatus()).isEqualTo(401);
        }
    }

    @Test
    @DisplayName("기본 모드(설정 없음)는 ENFORCE 다")
    void defaultsToEnforce() throws Exception {
        InternalApiAuthFilter filter = filter("", "blog:" + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(reachesController(filter, get("/internal/v1/deleted-users"), response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("WARN: 토큰이 없어도 통과하되 경고만 남긴다. OFF: 검사하지 않는다")
    void warnAndOffLetRequestsThrough() throws Exception {
        for (String mode : new String[]{"WARN", "OFF"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertThat(reachesController(filter(mode, "blog:" + TOKEN), get("/internal/v1/deleted-users"), response)).as(mode).isTrue();
            assertThat(response.getStatus()).as(mode).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("게이트웨이를 거친 요청(X-Forwarded-For, X-Real-IP)은 토큰이 맞아도, 모드가 OFF 여도 404 다")
    void proxiedRequestsAreAlwaysNotFound() throws Exception {
        for (String mode : new String[]{"ENFORCE", "WARN", "OFF"}) {
            for (String header : new String[]{"X-Forwarded-For", "X-Real-IP"}) {
                MockHttpServletRequest request = get("/internal/v1/deleted-users");
                request.addHeader("X-Doro-Service-Token", TOKEN);
                request.addHeader(header, "203.0.113.9");
                MockHttpServletResponse response = new MockHttpServletResponse();
                assertThat(reachesController(filter(mode, "blog:" + TOKEN), request, response)).as("%s/%s", mode, header).isFalse();
                assertThat(response.getStatus()).as("%s/%s", mode, header).isEqualTo(404);
            }
        }
    }

    @Test
    @DisplayName("/internal 이 아닌 경로(공개 API)는 건드리지 않는다")
    void ignoresOtherPaths() throws Exception {
        InternalApiAuthFilter filter = filter("ENFORCE", "blog:" + TOKEN);
        for (String path : new String[]{"/api/v1/auth/login", "/oauth2/token", "/.well-known/jwks.json", "/internal-docs", "/api/v1/internal/x"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertThat(reachesController(filter, get(path), response)).as(path).isTrue();
        }
    }

    @Test
    @DisplayName("퍼센트 인코딩·중복 슬래시·점 경로로 /internal 검사를 우회할 수 없다")
    void canonicalPathCannotBeBypassed() throws Exception {
        InternalApiAuthFilter filter = filter("ENFORCE", "blog:" + TOKEN);
        for (String path : new String[]{"/internal/v1/deleted-users", "//internal/v1/deleted-users", "/internal/./v1/deleted-users", "/api/../internal/v1/deleted-users"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertThat(reachesController(filter, request, response)).as(path).isFalse();
            assertThat(response.getStatus()).as(path).isEqualTo(401);
        }
    }

    @Test
    @DisplayName("모르는 모드 값이면 기동(생성)에 실패한다")
    void unknownModeFailsFast() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> filter("ENFORCED", "blog:" + TOKEN)).isInstanceOf(IllegalStateException.class);
    }
}

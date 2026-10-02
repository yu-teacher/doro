package com.hunnit_beasts.guard.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 내장 서버(Tomcat)에 퍼센트 인코딩된 경로로 요청해서, 서비스 토큰 필터가 디코딩 전 URI 때문에 건너뛰어지지 않는지 확인한다.
 * (MockHttpServletRequest 는 서버의 경로 디코딩·정규화를 거치지 않아 이 종류의 우회를 재현하지 못한다.)
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "doro.guard.security.mode=ENFORCE",
                "doro.guard.security.service-token=encoded-path-test-token-0123456789abcdef"
        })
@ActiveProfiles("test")
class ServiceTokenEncodedPathTest {

    @Value("${local.server.port}")
    private int port;

    private static final String TOKEN = "encoded-path-test-token-0123456789abcdef";

    private int get(String rawPath) throws Exception {
        return get(rawPath, null);
    }

    private int get(String rawPath, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + rawPath)).GET();
        if (token != null) {
            builder.header(ServiceAuthProperties.HEADER_NAME, token);
        }
        HttpRequest request = builder.build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }

    @Test
    @DisplayName("ENFORCE: 토큰 없는 요청은 정상 경로뿐 아니라 인코딩·변형된 경로에서도 인증 오류(401)여야 한다")
    void unauthenticatedRequestIsRejectedForEveryPathSpelling() throws Exception {
        assertThat(get("/api/v1/guard/schema")).as("기준: 정상 경로").isEqualTo(401);
        assertThat(get("/%61pi/v1/guard/schema")).as("첫 글자 인코딩").isEqualTo(401);
        assertThat(get("/api/v1/guard/%73chema")).as("마지막 구간 인코딩").isEqualTo(401);
        assertThat(get("/api/v1/%67uard/schema")).as("중간 구간 인코딩").isEqualTo(401);
        assertThat(get("/api/v1/guard/./schema")).as("점 세그먼트").isIn(401, 400, 404);
        assertThat(get("/api/v1//guard/schema")).as("중복 슬래시").isIn(401, 400, 404);
    }

    @Test
    @DisplayName("유효한 토큰이 있으면 인코딩된 경로로도 정상 처리되고, health 는 토큰 없이도 열려 있다")
    void validTokenWorksAndHealthStaysPublic() throws Exception {
        assertThat(get("/api/v1/guard/schema", TOKEN)).isEqualTo(200);
        assertThat(get("/%61pi/v1/guard/schema", TOKEN)).isEqualTo(200);
        assertThat(get("/actuator/health")).isEqualTo(200);
    }
}

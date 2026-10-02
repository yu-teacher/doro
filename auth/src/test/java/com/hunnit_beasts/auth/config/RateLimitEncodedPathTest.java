package com.hunnit_beasts.auth.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 내장 서버(Tomcat)에 퍼센트 인코딩된 경로로 요청해서, 속도 제한 필터가 디코딩 전 URI 문자열 비교 때문에 건너뛰어지지 않는지 확인한다.
 * (게이트웨이는 클라이언트가 보낸 원본 URI 를 그대로 전달하고, Spring MVC 는 디코딩한 경로로 컨트롤러를 찾는다.)
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "doro.iam.rate-limit.enabled=true",
                "doro.iam.rate-limit.login-max=3"
        })
@ActiveProfiles("test")
class RateLimitEncodedPathTest {

    @Value("${local.server.port}")
    private int port;

    private int postLogin(String rawPath) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + rawPath))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"nobody@doro.test\",\"password\":\"wrong-password\"}"))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private List<Integer> attempts(String rawPath, int count) throws Exception {
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            statuses.add(postLogin(rawPath));
        }
        return statuses;
    }

    @Test
    @DisplayName("기준: 정상 경로는 제한 횟수를 넘으면 429")
    void plainPathIsLimited() throws Exception {
        assertThat(attempts("/api/v1/auth/login", 6)).contains(429);
    }

    @Test
    @DisplayName("퍼센트 인코딩된 경로(/log%69n)로도 같은 제한이 적용된다")
    void encodedPathIsLimitedToo() throws Exception {
        // 다른 테스트의 요청과 IP 단위로 섞이지 않도록 별도의 호출 수로 충분히 넘긴다
        List<Integer> statuses = attempts("/api/v1/auth/log%69n", 12);

        // 제한 전에는 실제로 로그인 컨트롤러가 처리한다(잘못된 자격 증명 401). 404 로 끝나는 가짜 경로가 아니다.
        assertThat(statuses.get(0)).as("첫 요청은 로그인 컨트롤러에 도달").isEqualTo(401);
        assertThat(statuses).as("인코딩된 경로의 응답들").contains(429);
    }
}

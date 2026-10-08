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

/** 비밀번호 변경 API 에도 IP 단위 요청 제한이 걸린다. (계정 잠금은 계정 단위라 IP 단위 대량 시도를 막지 못한다) */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "doro.iam.rate-limit.enabled=true",
                // 다른 속도 제한 테스트와 설정이 같으면 컨텍스트(와 IP 별 횟수)를 공유하므로 값을 다르게 둔다
                "doro.iam.rate-limit.login-max=4"
        })
@ActiveProfiles("test")
class PasswordChangeRateLimitTest {

    @Value("${local.server.port}")
    private int port;

    private int putPassword(String rawPath) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + rawPath))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer not-a-real-token")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"currentPassword\":\"x\",\"newPassword\":\"yyyyyyyy\"}"))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    @DisplayName("비밀번호 변경 요청도 제한 횟수를 넘으면 429 (퍼센트 인코딩 경로 포함)")
    void passwordChangeIsRateLimited() throws Exception {
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            statuses.add(putPassword("/api/v1/users/me/passw%6Frd"));
        }
        assertThat(statuses.get(0)).as("제한 전에는 인증 단계에서 거부(401)").isEqualTo(401);
        assertThat(statuses).contains(429);
    }
}

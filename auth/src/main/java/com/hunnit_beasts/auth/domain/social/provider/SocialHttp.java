package com.hunnit_beasts.auth.domain.social.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/** 공급자 호출 공통부: 짧은 타임아웃, 폼 POST, Bearer GET, JSON 파싱. 오류는 모두 SocialLoginException 으로 바꾼다. */
final class SocialHttp {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient client;

    SocialHttp() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    JsonNode postForm(String url, Map<String, String> form) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        form.forEach(body::add);
        return parse(call(() -> client.post().uri(url)
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .body(String.class)));
    }

    JsonNode getJson(String url, String accessToken) {
        return parse(call(() -> client.get().uri(url)
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accessToken)
                .header("User-Agent", "doro-iam")
                .retrieve()
                .body(String.class)));
    }

    private static String call(java.util.function.Supplier<String> request) {
        try {
            return request.get();
        } catch (RuntimeException e) {
            // 응답 본문에 토큰이 들어갈 수 있어 메시지는 싣지 않는다.
            throw new SocialLoginException(SocialLoginException.PROVIDER_ERROR,
                    "소셜 공급자 호출에 실패했습니다: " + e.getClass().getSimpleName(), e);
        }
    }

    private static JsonNode parse(String body) {
        try {
            JsonNode node = MAPPER.readTree(body == null ? "" : body);
            if (node == null || node.isMissingNode()) {
                throw new SocialLoginException(SocialLoginException.PROVIDER_ERROR, "소셜 공급자 응답이 비어 있습니다.");
            }
            return node;
        } catch (SocialLoginException e) {
            throw e;
        } catch (Exception e) {
            throw new SocialLoginException(SocialLoginException.PROVIDER_ERROR, "소셜 공급자 응답을 읽을 수 없습니다.", e);
        }
    }
}

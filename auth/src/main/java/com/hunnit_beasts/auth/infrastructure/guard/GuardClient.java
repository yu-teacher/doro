package com.hunnit_beasts.auth.infrastructure.guard;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class GuardClient {

    private final RestClient restClient;

    public GuardClient(@Value("${doro.guard.url:http://localhost:28081}") String guardUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(3));

        this.restClient = RestClient.builder()
                .baseUrl(guardUrl)
                .requestFactory(requestFactory)
                .build();
        log.info("Initialized Doro GuardClient with target URL: {}", guardUrl);
    }

    public record CheckApiRequest(
            String namespace,
            String objectId,
            String relation,
            String subjectNamespace,
            String subjectId,
            String subjectRelation
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CheckApiResponse(
            boolean allowed,
            int depth,
            String reason
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GuardResponse<T>(
            boolean success,
            T data,
            String message
    ) {}

    public record TupleDto(
            String namespace,
            String objectId,
            String relation,
            String subjectNamespace,
            String subjectId,
            String subjectRelation
    ) {
        public static TupleDto of(String namespace, String objectId, String relation,
                                 String subjectNamespace, String subjectId) {
            return new TupleDto(namespace, objectId, relation, subjectNamespace, subjectId, "");
        }

        public static TupleDto of(String namespace, String objectId, String relation,
                                 String subjectNamespace, String subjectId, String subjectRelation) {
            return new TupleDto(namespace, objectId, relation, subjectNamespace, subjectId,
                    subjectRelation != null ? subjectRelation : "");
        }
    }

    /**
     * Doro Guard ReBAC 권한 검증 (Check)
     */
    public boolean check(String namespace, String objectId, String relation, String subjectId) {
        return check(namespace, objectId, relation, "user", subjectId, "");
    }

    public boolean check(String namespace, String objectId, String relation,
                         String subjectNamespace, String subjectId, String subjectRelation) {
        CheckApiRequest request = new CheckApiRequest(
                namespace,
                objectId,
                relation,
                subjectNamespace,
                subjectId,
                subjectRelation != null ? subjectRelation : ""
        );

        try {
            GuardResponse<CheckApiResponse> response = restClient.post()
                    .uri("/api/v1/guard/check")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<GuardResponse<CheckApiResponse>>() {});

            if (response != null && response.data() != null) {
                log.debug("Guard check result: {}:{}#{}@{}: allowed={}",
                        namespace, objectId, relation, subjectId, response.data().allowed());
                return response.data().allowed();
            }
            return false;
        } catch (Exception e) {
            log.error("Doro Guard ReBAC check failed for {}:{}#{}@{}: {}",
                    namespace, objectId, relation, subjectId, e.getMessage());
            // Fail-closed 보안 원칙: 인가 서버 장애 시 접근 거부
            return false;
        }
    }

    /**
     * 관계 튜플 일괄 등록
     */
    public int writeTuples(List<TupleDto> tuples) {
        if (tuples == null || tuples.isEmpty()) {
            return 0;
        }
        try {
            GuardResponse<Map<String, Integer>> response = restClient.post()
                    .uri("/api/v1/guard/tuples")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(tuples)
                    .retrieve()
                    .body(new ParameterizedTypeReference<GuardResponse<Map<String, Integer>>>() {});

            if (response != null && response.data() != null && response.data().containsKey("writtenCount")) {
                return response.data().get("writtenCount");
            }
            return 0;
        } catch (Exception e) {
            log.error("Doro Guard writeTuples failed: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 관계 튜플 일괄 삭제
     */
    public int deleteTuples(List<TupleDto> tuples) {
        if (tuples == null || tuples.isEmpty()) {
            return 0;
        }
        try {
            GuardResponse<Map<String, Integer>> response = restClient.method(HttpMethod.DELETE)
                    .uri("/api/v1/guard/tuples")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(tuples)
                    .retrieve()
                    .body(new ParameterizedTypeReference<GuardResponse<Map<String, Integer>>>() {});

            if (response != null && response.data() != null && response.data().containsKey("deletedCount")) {
                return response.data().get("deletedCount");
            }
            return 0;
        } catch (Exception e) {
            log.error("Doro Guard deleteTuples failed: {}", e.getMessage());
            return 0;
        }
    }
}

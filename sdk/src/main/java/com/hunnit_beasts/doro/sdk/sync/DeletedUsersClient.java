package com.hunnit_beasts.doro.sdk.sync;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Doro IAM 의 내부 API(게이트웨이를 거치지 않는 Docker 내부 주소)를 호출하는 서버 간 클라이언트.
 * IAM 이 호출자를 확인하도록 서비스 토큰(헤더 {@value #TOKEN_HEADER})을 함께 보낸다. 토큰은 설정으로만 주고 로그에 남기지 않는다.
 */
public class DeletedUsersClient {

    private static final Logger log = LoggerFactory.getLogger(DeletedUsersClient.class);

    public static final String TOKEN_HEADER = "X-Doro-Service-Token";

    public record DeletedUser(UUID userId, Instant deletedAt) {}

    public record DeletedUsersPage(List<DeletedUser> items, Instant nextSince) {}

    private record Envelope(DeletedUsersPage data) {}

    private final String baseUrl;
    private final String internalToken;
    private final RestClient http;

    public DeletedUsersClient(String baseUrl, String internalToken, DeletedUsersProperties props) {
        this.baseUrl = baseUrl;
        this.internalToken = internalToken == null ? "" : internalToken.trim();
        if (this.internalToken.isEmpty()) {
            log.warn("doro.iam.internal-token is not set: Doro IAM rejects internal API calls unless its internal auth mode is WARN/OFF");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) props.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) props.getReadTimeout().toMillis());
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /** since 이후(포함)에 영구 탈퇴한 사용자를 오래된 순으로 가져온다. IAM 에 닿지 못하면 예외를 던진다. */
    public DeletedUsersPage fetchDeletedUsers(Instant since, int limit) {
        String uri = UriComponentsBuilder.fromUriString(baseUrl)
                .path("/internal/v1/deleted-users")
                .queryParam("since", since)
                .queryParam("limit", limit)
                .build()
                .toUriString();
        Envelope body = http.get().uri(uri)
                .headers(headers -> {
                    if (!internalToken.isEmpty()) {
                        headers.set(TOKEN_HEADER, internalToken);
                    }
                })
                .retrieve().body(new ParameterizedTypeReference<Envelope>() {});
        if (body == null || body.data() == null || body.data().items() == null || body.data().nextSince() == null) {
            throw new IllegalStateException("Doro IAM returned an unexpected deleted-users response");
        }
        return body.data();
    }
}

package com.hunnit_beasts.doro.sdk.sync;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 탈퇴 동기화: 페이지 이어받기, 기준 시각(겹침 포함), 실패 후 이어서 처리, 멱등, 서비스 토큰 헤더. */
class DeletedUsersSyncTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    private HttpServer server;
    private final List<String> requestUris = new CopyOnWriteArrayList<>();
    private final List<String> tokenHeaders = new CopyOnWriteArrayList<>();
    private volatile List<String> pagesByCall = List.of();   // 호출마다 줄 JSON
    private volatile int status = 200;
    private final Map<String, Instant> cursorStore = new ConcurrentHashMap<>();
    private final SyncCursorStore cursors = new SyncCursorStore() {
        @Override public Optional<Instant> get(String name) { return Optional.ofNullable(cursorStore.get(name)); }
        @Override public void advance(String name, Instant cursor) { cursorStore.merge(name, cursor, (a, b) -> b.isAfter(a) ? b : a); }
    };
    private final Set<UUID> handled = new HashSet<>();
    private DeletedUsersProperties props;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/deleted-users", ex -> {
            requestUris.add(ex.getRequestURI().toString());
            tokenHeaders.add(String.valueOf(ex.getRequestHeaders().getFirst(DeletedUsersClient.TOKEN_HEADER)));
            int call = requestUris.size() - 1;
            String body = status == 200 ? pagesByCall.get(Math.min(call, pagesByCall.size() - 1)) : "{}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        props = new DeletedUsersProperties();
        props.setPageSize(2);
        props.setOverlap(Duration.ofMinutes(10));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private DeletedUsersSync sync(DeletedUserHandler handler) {
        DeletedUsersClient client = new DeletedUsersClient("http://127.0.0.1:" + server.getAddress().getPort(), "svc-token", props);
        return new DeletedUsersSync(client, handler, cursors, props, Clock.fixed(T0.plusSeconds(3600), ZoneOffset.UTC));
    }

    private static String page(Instant nextSince, UUID... users) {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < users.length; i++) {
            items.add("{\"userId\":\"" + users[i] + "\",\"deletedAt\":\"" + T0.plusSeconds(i) + "\"}");
        }
        return "{\"data\":{\"items\":[" + String.join(",", items) + "],\"nextSince\":\"" + nextSince + "\"}}";
    }

    @Test
    @DisplayName("한 페이지에 다 들어오면 처리하고 기준 시각을 nextSince - 겹침 으로 저장한다")
    void singlePage() {
        UUID a = UUID.randomUUID();
        pagesByCall = List.of(page(T0.plusSeconds(100), a));

        int count = sync((id, at) -> handled.add(id)).sync();

        assertThat(count).isEqualTo(1);
        assertThat(handled).containsExactly(a);
        assertThat(cursorStore.get(props.getName())).isEqualTo(T0.plusSeconds(100).minus(Duration.ofMinutes(10)));
        assertThat(tokenHeaders).containsOnly("svc-token");
    }

    @Test
    @DisplayName("페이지가 가득 차면 nextSince 로 이어서 다음 페이지를 가져온다")
    void followsPages() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        pagesByCall = List.of(page(T0.plusSeconds(10), a, b), page(T0.plusSeconds(20), c));

        int count = sync((id, at) -> handled.add(id)).sync();

        assertThat(count).isEqualTo(3);
        assertThat(requestUris).hasSize(2);
        assertThat(requestUris.get(1)).contains("since=" + T0.plusSeconds(10));
    }

    @Test
    @DisplayName("이미 처리된 사용자(false)는 세지 않고, 같은 사용자를 다시 받아도 결과가 같다(멱등)")
    void idempotent() {
        UUID a = UUID.randomUUID();
        pagesByCall = List.of(page(T0.plusSeconds(5), a));
        DeletedUsersSync s = sync((id, at) -> handled.add(id));

        assertThat(s.sync()).isEqualTo(1);
        assertThat(s.sync()).as("두 번째는 이미 처리됨").isZero();
    }

    @Test
    @DisplayName("처리 도중 실패하면 예외를 던지고 이미 저장한 기준 시각은 유지된다(다음 실행이 이어서 처리)")
    void failureKeepsProgress() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        pagesByCall = List.of(page(T0.plusSeconds(10), a, b), page(T0.plusSeconds(20), c));
        DeletedUsersSync s = sync((id, at) -> {
            if (id.equals(c)) {
                throw new IllegalStateException("db down");
            }
            return handled.add(id);
        });

        assertThatThrownBy(s::sync).isInstanceOf(IllegalStateException.class);
        assertThat(cursorStore.get(props.getName())).as("첫 페이지까지만 저장").isEqualTo(T0.plusSeconds(10).minus(Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("기준 시각은 뒤로 가지 않는다")
    void cursorNeverMovesBackwards() {
        cursorStore.put(props.getName(), T0.plusSeconds(1000));
        pagesByCall = List.of(page(T0.plusSeconds(5)));

        sync((id, at) -> true).sync();

        assertThat(cursorStore.get(props.getName())).isEqualTo(T0.plusSeconds(1000));
    }

    @Test
    @DisplayName("IAM 이 5xx 면 예외로 알리고, scheduledRun 은 삼킨 뒤 다음 주기를 기다린다")
    void iamErrors() {
        status = 503;
        DeletedUsersSync s = sync((id, at) -> true);

        assertThatThrownBy(s::sync).isInstanceOf(RuntimeException.class);
        s.scheduledRun();   // 예외가 밖으로 나가지 않는다
    }

    @Test
    @DisplayName("한 페이지가 전부 같은 시각이면 무한 반복하지 않고 멈춘다")
    void stopsWhenCannotAdvance() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        pagesByCall = List.of(page(T0, a, b));   // nextSince == since(EPOCH 가 아니라 T0 부터 시작하도록 기준을 둔다)
        cursorStore.put(props.getName(), T0.plus(Duration.ofMinutes(10)));

        int count = sync((id, at) -> handled.add(id)).sync();

        assertThat(count).isEqualTo(2);
        assertThat(requestUris).as("같은 자리에서 반복 호출하지 않는다").hasSize(1);
    }

    @Test
    @DisplayName("enabled=false 면 아무 것도 하지 않는다")
    void disabled() {
        props.setEnabled(false);

        sync((id, at) -> true).scheduledRun();

        assertThat(requestUris).isEmpty();
    }

    @Test
    @DisplayName("서비스 토큰이 비어 있으면 헤더를 보내지 않는다")
    void noTokenNoHeader() {
        pagesByCall = List.of(page(T0.plusSeconds(5)));
        DeletedUsersClient client = new DeletedUsersClient("http://127.0.0.1:" + server.getAddress().getPort(), "  ", props);

        client.fetchDeletedUsers(T0, 10);

        assertThat(tokenHeaders).containsExactly("null");
    }
}

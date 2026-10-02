package com.hunnit_beasts.doro.sdk.security.revocation;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** 테스트용 가짜 IAM: GET /api/v1/sessions/current 에 설정된 상태 코드로 응답하고 호출을 기록한다. */
public final class FakeIamServer implements AutoCloseable {

    private final HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();
    private volatile int status = 204;
    private volatile long delayMillis = 0;

    public FakeIamServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/v1/sessions/current", exchange -> {
            hits.incrementAndGet();
            authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            try {
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
                exchange.sendResponseHeaders(status, status == 204 ? -1 : 0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // 클라이언트가 타임아웃으로 연결을 끊은 경우
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String url() {
        return "http://127.0.0.1:" + port() + "/api/v1/sessions/current";
    }

    public String jwksUri() {
        return "http://127.0.0.1:" + port() + "/.well-known/jwks.json";
    }

    public void respondWith(int status) {
        this.status = status;
    }

    public void delay(long millis) {
        this.delayMillis = millis;
    }

    public int hits() {
        return hits.get();
    }

    public List<String> authorizationHeaders() {
        return authorizationHeaders;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** 수동으로 시간을 진행시키는 시계 */
    public static final class MutableClock extends Clock {
        private volatile long millis = System.currentTimeMillis();

        public void advanceSeconds(long seconds) {
            millis += seconds * 1000L;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }
}

package com.hunnit_beasts.doro.sdk.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DoroGuardClientResilienceTest {

    @Test
    @DisplayName("장애 격리 (Resilience & Fail-Closed): gRPC 서버가 다운되어 접속 불가 시 크래시 없이 안전하게 false 반환")
    void testClientFailClosedWhenServerUnreachable() {
        // 존재하지 않는 가짜 포트로 클라이언트 생성
        DoroGuardClient unreachableClient = new DoroGuardClient("localhost", 59999);

        try {
            // 서버 접속 실패 시 애플리케이션 크래시 없이 false 반환 검증
            boolean result = unreachableClient.check("document", "doc-1", "viewer", "user-1");
            assertThat(result).isFalse();

            int writeCount = unreachableClient.writeTuple("document", "doc-1", "viewer", "user", "user-1");
            assertThat(writeCount).isEqualTo(0);

            int deleteCount = unreachableClient.deleteTuple("document", "doc-1", "viewer", "user", "user-1");
            assertThat(deleteCount).isEqualTo(0);
        } finally {
            unreachableClient.shutdown();
        }
    }
}

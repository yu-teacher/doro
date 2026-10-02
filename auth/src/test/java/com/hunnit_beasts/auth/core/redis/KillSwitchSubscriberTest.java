package com.hunnit_beasts.auth.core.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 발행 쪽(KillSwitchPublisher)이 만든 JSON 을 구독 쪽이 그대로 읽을 수 있어야 한다. */
class KillSwitchSubscriberTest {

    /** KillSwitchPublisher 와 같은 설정으로 직렬화한 실제 메시지 본문 */
    private static String publishedPayload(KillSwitchEvent event) throws Exception {
        return new ObjectMapper().findAndRegisterModules().writeValueAsString(event);
    }

    @Test
    @DisplayName("세션 폐기 이벤트(Instant 타임스탬프 포함)를 구독 쪽이 파싱한다")
    void parsesSessionRevokedEventWithInstantTimestamp() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        KillSwitchEvent sent = KillSwitchEvent.ofSessionRevoked(userId, sessionId, "LOGOUT");

        KillSwitchEvent received = new KillSwitchSubscriber().parse(publishedPayload(sent));

        assertThat(received.eventType()).isEqualTo("SESSION_REVOKED");
        assertThat(received.userId()).isEqualTo(userId);
        assertThat(received.sessionId()).isEqualTo(sessionId);
        assertThat(received.reason()).isEqualTo("LOGOUT");
        assertThat(received.timestamp()).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
    }

    @Test
    @DisplayName("토큰 계열 폐기 이벤트도 파싱한다")
    void parsesFamilyRevokedEvent() throws Exception {
        UUID familyId = UUID.randomUUID();
        KillSwitchEvent received = new KillSwitchSubscriber()
                .parse(publishedPayload(KillSwitchEvent.ofFamilyRevoked(UUID.randomUUID(), familyId, "REUSE_ATTACK_DETECTED")));

        assertThat(received.eventType()).isEqualTo("FAMILY_REVOKED");
        assertThat(received.familyId()).isEqualTo(familyId);
    }
}

package com.hunnit_beasts.auth.core.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class KillSwitchSubscriber implements MessageListener {

    // 발행 쪽(KillSwitchPublisher)과 같은 설정: KillSwitchEvent 의 Instant 를 읽으려면 java.time 모듈이 필요하다.
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    KillSwitchEvent parse(String body) throws JsonProcessingException {
        return objectMapper.readValue(body, KillSwitchEvent.class);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            KillSwitchEvent event = parse(new String(message.getBody(), StandardCharsets.UTF_8));
            log.info("Received KillSwitch event: type={}, sessionId={}, familyId={}, reason={}",
                    event.eventType(), event.sessionId(), event.familyId(), event.reason());
        } catch (Exception e) {
            log.error("Failed to parse KillSwitch message", e);
        }
    }
}

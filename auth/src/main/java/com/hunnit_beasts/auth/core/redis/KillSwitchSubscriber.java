package com.hunnit_beasts.auth.core.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class KillSwitchSubscriber implements MessageListener {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            KillSwitchEvent event = objectMapper.readValue(body, KillSwitchEvent.class);
            log.info("Received KillSwitch event: type={}, sessionId={}, familyId={}, reason={}",
                    event.eventType(), event.sessionId(), event.familyId(), event.reason());
        } catch (Exception e) {
            log.error("Failed to parse KillSwitch message", e);
        }
    }
}

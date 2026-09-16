package com.hunnit_beasts.auth.core.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunnit_beasts.auth.config.RedisConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
public class KillSwitchPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ChannelTopic killSwitchTopic;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KillSwitchPublisher(
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            @Autowired(required = false) ChannelTopic killSwitchTopic) {
        this.redisTemplate = redisTemplate;
        this.killSwitchTopic = killSwitchTopic != null ? killSwitchTopic : new ChannelTopic("doro.iam.killswitch");
    }

    public void publishSessionRevoked(UUID userId, UUID sessionId, String reason) {
        if (redisTemplate == null) return;
        try {
            // 1. Redis 블랙리스트 키 등록 (7일 TTL)
            String blacklistKey = "doro:blacklist:session:" + sessionId;
            redisTemplate.opsForValue().set(blacklistKey, reason != null ? reason : "REVOKED", Duration.ofDays(7));

            // 2. Pub/Sub 이벤트 발행
            KillSwitchEvent event = KillSwitchEvent.ofSessionRevoked(userId, sessionId, reason);
            String payload = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(killSwitchTopic.getTopic(), payload);

            log.info("KillSwitch published for session: sessionId={}, reason={}", sessionId, reason);
        } catch (Exception e) {
            log.warn("Failed to publish KillSwitch event to Redis: {}", e.getMessage());
        }
    }

    public void publishFamilyRevoked(UUID userId, UUID familyId, String reason) {
        if (redisTemplate == null) return;
        try {
            // 1. Redis 블랙리스트 키 등록 (14일 TTL)
            String blacklistKey = "doro:blacklist:family:" + familyId;
            redisTemplate.opsForValue().set(blacklistKey, reason != null ? reason : "REUSE_ATTACK", Duration.ofDays(14));

            // 2. Pub/Sub 이벤트 발행
            KillSwitchEvent event = KillSwitchEvent.ofFamilyRevoked(userId, familyId, reason);
            String payload = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(killSwitchTopic.getTopic(), payload);

            log.warn("KillSwitch published for family: familyId={}, reason={}", familyId, reason);
        } catch (Exception e) {
            log.warn("Failed to publish KillSwitch event to Redis: {}", e.getMessage());
        }
    }

    public boolean isSessionBlacklisted(UUID sessionId) {
        if (redisTemplate == null) return false;
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey("doro:blacklist:session:" + sessionId));
        } catch (Exception e) {
            return false;
        }
    }
}

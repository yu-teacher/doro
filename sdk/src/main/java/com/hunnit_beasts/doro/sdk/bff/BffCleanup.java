package com.hunnit_beasts.doro.sdk.bff;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;

/** 만료된 BFF 세션과 끝나지 않은 로그인 시도를 주기적으로 지운다(서비스에 {@code @EnableScheduling} 이 있어야 돈다). */
public class BffCleanup {

    private static final Logger log = LoggerFactory.getLogger(BffCleanup.class);

    private final BffSessionStore sessions;
    private final BffLoginAttemptStore attempts;

    public BffCleanup(BffSessionStore sessions, BffLoginAttemptStore attempts) {
        this.sessions = sessions;
        this.attempts = attempts;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    public void purgeExpired() {
        Instant now = Instant.now();
        int sessionCount = sessions.deleteExpired(now);
        int attemptCount = attempts.deleteExpired(now);
        if (sessionCount > 0 || attemptCount > 0) {
            log.info("Purged expired BFF rows: sessions={}, loginAttempts={}", sessionCount, attemptCount);
        }
    }
}

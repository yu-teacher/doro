package com.hunnit_beasts.auth.domain.session.service;

import com.hunnit_beasts.auth.domain.session.repository.UserSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** 무활동 기간이 지난 세션을 주기적으로 비활성화한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionCleanupScheduler {

    private final UserSessionRepository sessionRepository;

    @Scheduled(fixedDelayString = "${doro.iam.session.cleanup-interval-ms:600000}",
            initialDelayString = "${doro.iam.session.cleanup-initial-delay-ms:60000}")
    @Transactional
    public void deactivateExpiredSessions() {
        int deactivated = sessionRepository.deactivateExpiredSessions(Instant.now());
        if (deactivated > 0) {
            log.info("Deactivated {} expired session(s)", deactivated);
        }
    }
}

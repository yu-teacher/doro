package com.hunnit_beasts.auth.domain.credential.service;

import com.hunnit_beasts.auth.domain.credential.entity.Credential;
import com.hunnit_beasts.auth.domain.credential.repository.CredentialRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CredentialService {

    private final CredentialRepository credentialRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailedAttempt(UUID userId) {
        if (credentialRepository.incrementFailedAttempts(userId, Instant.now()) == 0) {
            return;
        }
        Instant lockedUntil = Instant.now().plus(Credential.LOCK_DURATION_MINUTES, ChronoUnit.MINUTES);
        boolean locked = credentialRepository.lockIfThresholdReached(userId, Credential.MAX_FAILED_ATTEMPTS, lockedUntil) > 0;
        log.warn("Failed attempt recorded for userId={}, locked={}", userId, locked);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resetFailedAttempts(UUID userId) {
        credentialRepository.findByUserId(userId).ifPresent(credential -> {
            if (credential.getFailedAttempts() > 0 || credential.getLockedUntil() != null) {
                credential.resetFailedAttempts();
                credentialRepository.saveAndFlush(credential);
            }
        });
    }
}

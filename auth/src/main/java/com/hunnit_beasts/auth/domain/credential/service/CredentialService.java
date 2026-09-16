package com.hunnit_beasts.auth.domain.credential.service;

import com.hunnit_beasts.auth.domain.credential.entity.Credential;
import com.hunnit_beasts.auth.domain.credential.repository.CredentialRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CredentialService {

    private final CredentialRepository credentialRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailedAttempt(UUID userId) {
        credentialRepository.findByUserId(userId).ifPresent(credential -> {
            credential.recordFailedAttempt();
            credentialRepository.saveAndFlush(credential);
            log.warn("Failed attempt recorded for userId={}. Total failed: {}, Locked until: {}",
                    userId, credential.getFailedAttempts(), credential.getLockedUntil());
        });
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

package com.hunnit_beasts.auth.domain.credential.entity;

import com.hunnit_beasts.auth.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "credentials")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Credential extends BaseTimeEntity {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int LOCK_DURATION_MINUTES = 15;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "totp_secret", length = 64)
    private String totpSecret;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Builder
    public Credential(UUID id, UUID userId, String passwordHash, String totpSecret) {
        this.id = id != null ? id : UUID.randomUUID();
        this.userId = userId;
        this.passwordHash = passwordHash;
        this.totpSecret = totpSecret;
        this.failedAttempts = 0;
        this.lockedUntil = null;
    }

    public void updatePassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
        resetFailedAttempts();
    }

    public void recordFailedAttempt() {
        this.failedAttempts++;
        if (this.failedAttempts >= MAX_FAILED_ATTEMPTS) {
            this.lockedUntil = Instant.now().plus(LOCK_DURATION_MINUTES, ChronoUnit.MINUTES);
        }
    }

    public void resetFailedAttempts() {
        this.failedAttempts = 0;
        this.lockedUntil = null;
    }

    public boolean isLocked() {
        if (this.lockedUntil == null) {
            return false;
        }
        if (Instant.now().isAfter(this.lockedUntil)) {
            // Lock duration expired
            resetFailedAttempts();
            return false;
        }
        return true;
    }

    public void updateTotpSecret(String totpSecret) {
        this.totpSecret = totpSecret;
    }
}

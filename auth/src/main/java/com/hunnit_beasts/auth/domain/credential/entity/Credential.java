package com.hunnit_beasts.auth.domain.credential.entity;

import com.hunnit_beasts.auth.common.entity.BaseTimeEntity;
import com.hunnit_beasts.auth.core.totp.TotpSecretConverters;
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

    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final int LOCK_DURATION_MINUTES = 15;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    /** 평문으로 보이지만 DB 에는 암호문으로 저장된다(TotpSecretConverters, 키가 설정된 경우). */
    @Convert(converter = TotpSecretConverters.Active.class)
    @Column(name = "totp_secret", length = 255)
    private String totpSecret;

    /** 2FA 등록 절차 중(코드 확인 전)인 시크릿. 확인이 끝나면 totpSecret 으로 승격된다. */
    @Convert(converter = TotpSecretConverters.Pending.class)
    @Column(name = "pending_totp_secret", length = 255)
    private String pendingTotpSecret;

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

    public boolean hasActiveTotp() {
        return totpSecret != null && !totpSecret.isBlank();
    }

    public boolean hasPendingTotp() {
        return pendingTotpSecret != null && !pendingTotpSecret.isBlank();
    }

    public void beginTotpEnrollment(String secret) {
        this.pendingTotpSecret = secret;
    }

    /** 대기 중인 시크릿을 활성 시크릿으로 승격한다. */
    public void confirmTotpEnrollment() {
        this.totpSecret = this.pendingTotpSecret;
        this.pendingTotpSecret = null;
    }

    public void clearPendingTotp() {
        this.pendingTotpSecret = null;
    }
}

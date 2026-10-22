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

    /** false 면 비밀번호를 정한 적이 없다(소셜 가입, 또는 이메일 소유 확인 전의 옛 비밀번호를 버린 계정). 저장된 해시는 아무도 모르는 값이다. */
    @Column(name = "password_set", nullable = false)
    private boolean passwordSet = true;

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
        this.passwordSet = true;
        resetFailedAttempts();
    }

    /** 비밀번호와 2FA 를 버린다. 해시는 아무도 모르는 무작위 값으로 바꿔 비밀번호 로그인을 막는다. */
    public void discardPassword(String unusableHash) {
        this.passwordHash = unusableHash;
        this.passwordSet = false;
        this.totpSecret = null;
        this.pendingTotpSecret = null;
        resetFailedAttempts();
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

package com.hunnit_beasts.auth.domain.token.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "is_revoked", nullable = false)
    private boolean isRevoked;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Builder
    public RefreshToken(UUID id, UUID sessionId, String tokenHash, UUID familyId, Instant expiresAt) {
        this.id = id != null ? id : UUID.randomUUID();
        this.sessionId = sessionId;
        this.tokenHash = tokenHash;
        this.familyId = familyId != null ? familyId : UUID.randomUUID();
        this.isRevoked = false;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public void revoke() {
        this.isRevoked = true;
    }

    public boolean isValid() {
        return !isRevoked && Instant.now().isBefore(this.expiresAt);
    }
}

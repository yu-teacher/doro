package com.hunnit_beasts.auth.domain.session.entity;

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
@Table(name = "user_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserSession {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "user_index", nullable = false)
    private int userIndex;

    @Column(name = "device_info", length = 255)
    private String deviceInfo;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @Column(name = "last_active_at", nullable = false)
    private Instant lastActiveAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Builder
    public UserSession(UUID id, UUID userId, int userIndex, String deviceInfo,
                       String ipAddress, String userAgent, Instant expiresAt) {
        this.id = id != null ? id : UUID.randomUUID();
        this.userId = userId;
        this.userIndex = userIndex;
        this.deviceInfo = deviceInfo;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.isActive = true;
        this.lastActiveAt = Instant.now();
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public void touch() {
        this.lastActiveAt = Instant.now();
    }

    public void deactivate() {
        this.isActive = false;
    }

    public boolean isExpired() {
        return !isActive || Instant.now().isAfter(this.expiresAt);
    }
}

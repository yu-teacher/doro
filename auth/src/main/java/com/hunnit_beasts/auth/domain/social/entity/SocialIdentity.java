package com.hunnit_beasts.auth.domain.social.entity;

import com.hunnit_beasts.auth.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** Doro 계정에 연결된 외부(소셜) 계정. (provider, providerUserId) 가 외부 계정의 고유 식별이다. */
@Entity
@Table(name = "social_identities")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SocialIdentity extends BaseTimeEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "provider", nullable = false, length = 30)
    private String provider;

    @Column(name = "provider_user_id", nullable = false, length = 100)
    private String providerUserId;

    /** 연결 당시 공급자가 알려 준 이메일(참고용). */
    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    public SocialIdentity(UUID userId, String provider, String providerUserId, String email) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.email = email;
        this.lastLoginAt = Instant.now();
    }

    public void touchLogin() {
        this.lastLoginAt = Instant.now();
    }
}

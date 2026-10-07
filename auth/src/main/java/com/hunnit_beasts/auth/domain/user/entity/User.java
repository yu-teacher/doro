package com.hunnit_beasts.auth.domain.user.entity;

import com.hunnit_beasts.auth.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "profile_image_url", columnDefinition = "TEXT")
    private String profileImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private UserStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    private UserRole role;

    /** 탈퇴를 요청한 시각. PENDING_DELETION 인 동안에만 값이 있다. */
    @Column(name = "deletion_requested_at")
    private Instant deletionRequestedAt;

    /** 개인정보를 영구 익명화한 시각. DELETED 인 계정에만 값이 있다. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Builder
    public User(UUID id, String email, String name, String profileImageUrl, UserStatus status, UserRole role) {
        this.id = id != null ? id : UUID.randomUUID();
        this.email = email;
        this.name = name;
        this.profileImageUrl = profileImageUrl;
        this.status = status != null ? status : UserStatus.ACTIVE;
        this.role = role != null ? role : UserRole.USER;
    }

    public void changeRole(UserRole newRole) {
        if (newRole != null) {
            this.role = newRole;
        }
    }

    public void updateStatus(UserStatus newStatus) {
        this.status = newStatus;
    }

    /**
     * 부분 수정: null 필드는 변경하지 않는다. profileImageUrl 이 빈 문자열이면 이미지를 제거한다.
     */
    public void updateProfile(String name, String profileImageUrl) {
        if (name != null && !name.isBlank()) {
            this.name = name.trim();
        }
        if (profileImageUrl != null) {
            this.profileImageUrl = profileImageUrl.isEmpty() ? null : profileImageUrl;
        }
    }

    public boolean isActive() {
        return this.status == UserStatus.ACTIVE;
    }

    public boolean isPendingDeletion() {
        return this.status == UserStatus.PENDING_DELETION;
    }

    public void requestDeletion(Instant now) {
        this.status = UserStatus.PENDING_DELETION;
        this.deletionRequestedAt = now;
    }
}

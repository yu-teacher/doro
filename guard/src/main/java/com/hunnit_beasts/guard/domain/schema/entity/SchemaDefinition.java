package com.hunnit_beasts.guard.domain.schema.entity;

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
@Table(name = "schema_definitions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SchemaDefinition {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "version", nullable = false, unique = true)
    private int version;

    @Column(name = "dsl_text", nullable = false, columnDefinition = "TEXT")
    private String dslText;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Builder
    public SchemaDefinition(UUID id, int version, String dslText, boolean isActive) {
        this.id = id != null ? id : UUID.randomUUID();
        this.version = version;
        this.dslText = dslText;
        this.isActive = isActive;
        this.createdAt = Instant.now();
    }

    public void deactivate() {
        this.isActive = false;
    }
}

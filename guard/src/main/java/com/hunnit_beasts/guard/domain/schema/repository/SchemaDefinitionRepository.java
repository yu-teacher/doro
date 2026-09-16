package com.hunnit_beasts.guard.domain.schema.repository;

import com.hunnit_beasts.guard.domain.schema.entity.SchemaDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface SchemaDefinitionRepository extends JpaRepository<SchemaDefinition, UUID> {

    Optional<SchemaDefinition> findTopByIsActiveTrueOrderByVersionDesc();

    @Query("SELECT COALESCE(MAX(s.version), 0) FROM SchemaDefinition s")
    int findMaxVersion();
}

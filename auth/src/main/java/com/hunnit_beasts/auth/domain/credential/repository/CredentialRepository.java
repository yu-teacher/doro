package com.hunnit_beasts.auth.domain.credential.repository;

import com.hunnit_beasts.auth.domain.credential.entity.Credential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CredentialRepository extends JpaRepository<Credential, UUID> {
    Optional<Credential> findByUserId(UUID userId);
    void deleteByUserId(UUID userId);
}

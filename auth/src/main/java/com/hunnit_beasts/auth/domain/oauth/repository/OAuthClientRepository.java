package com.hunnit_beasts.auth.domain.oauth.repository;

import com.hunnit_beasts.auth.domain.oauth.entity.OAuthClient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OAuthClientRepository extends JpaRepository<OAuthClient, UUID> {
    Optional<OAuthClient> findByClientId(String clientId);

    boolean existsByClientId(String clientId);

    List<OAuthClient> findAllByOrderByCreatedAtDesc();
}

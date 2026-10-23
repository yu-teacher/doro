package com.hunnit_beasts.auth.domain.social.repository;

import com.hunnit_beasts.auth.domain.social.entity.SocialIdentity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SocialIdentityRepository extends JpaRepository<SocialIdentity, UUID> {

    Optional<SocialIdentity> findByProviderAndProviderUserId(String provider, String providerUserId);

    Optional<SocialIdentity> findByUserIdAndProvider(UUID userId, String provider);

    void deleteByUserId(UUID userId);
}

package com.hunnit_beasts.auth.domain.oauth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 등록된 OAuth 클라이언트(공개 클라이언트, PKCE 전용). */
@Entity
@Table(name = "oauth_clients")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OAuthClient {

    /** redirect_uris 컬럼의 구분자 */
    private static final String URI_SEPARATOR = "\n";
    /** allowed_scopes 컬럼의 구분자 */
    private static final String SCOPE_SEPARATOR = " ";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "client_id", nullable = false, unique = true, length = 100, updatable = false)
    private String clientId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "redirect_uris", nullable = false, columnDefinition = "TEXT")
    private String redirectUris;

    @Column(name = "allowed_scopes", nullable = false, length = 200)
    private String allowedScopes;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    /** 운영자가 직접 만든 자사 서비스(예: 블로그). true 이면 사용자에게 동의 화면을 보여 주지 않는다. */
    @Column(name = "first_party", nullable = false)
    private boolean firstParty;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Builder
    public OAuthClient(UUID id, String clientId, String name, List<String> redirectUris, Set<String> allowedScopes, boolean firstParty) {
        this.id = id != null ? id : UUID.randomUUID();
        this.clientId = clientId;
        this.name = name;
        this.redirectUris = String.join(URI_SEPARATOR, redirectUris);
        this.allowedScopes = String.join(SCOPE_SEPARATOR, allowedScopes);
        this.firstParty = firstParty;
        this.isActive = true;
        this.createdAt = Instant.now();
    }

    public List<String> redirectUriList() {
        return Arrays.stream(redirectUris.split(URI_SEPARATOR)).filter(s -> !s.isEmpty()).toList();
    }

    public Set<String> allowedScopeSet() {
        return new LinkedHashSet<>(Arrays.stream(allowedScopes.split(SCOPE_SEPARATOR)).filter(s -> !s.isEmpty()).toList());
    }

    public void deactivate() {
        this.isActive = false;
    }
}

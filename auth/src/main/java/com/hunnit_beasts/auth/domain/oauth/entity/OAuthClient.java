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

/** 등록된 OAuth 클라이언트. 시크릿이 없으면 공개 클라이언트(PKCE 만), 있으면 기밀 클라이언트(PKCE + client_secret). */
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

    /** client_secret 의 SHA-256 해시(hex). null 이면 공개 클라이언트. 평문은 저장하지 않는다. */
    @Column(name = "client_secret_hash", length = 64)
    private String clientSecretHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Builder
    public OAuthClient(UUID id, String clientId, String name, List<String> redirectUris, Set<String> allowedScopes, boolean firstParty, String clientSecretHash) {
        this.id = id != null ? id : UUID.randomUUID();
        this.clientId = clientId;
        this.name = name;
        this.redirectUris = String.join(URI_SEPARATOR, redirectUris);
        this.allowedScopes = String.join(SCOPE_SEPARATOR, allowedScopes);
        this.firstParty = firstParty;
        this.clientSecretHash = clientSecretHash;
        this.isActive = true;
        this.createdAt = Instant.now();
    }

    public List<String> redirectUriList() {
        return Arrays.stream(redirectUris.split(URI_SEPARATOR)).filter(s -> !s.isEmpty()).toList();
    }

    public Set<String> allowedScopeSet() {
        return new LinkedHashSet<>(Arrays.stream(allowedScopes.split(SCOPE_SEPARATOR)).filter(s -> !s.isEmpty()).toList());
    }

    public boolean isConfidential() {
        return clientSecretHash != null;
    }

    /** 시크릿을 새로 지정(회전 포함). 이전 시크릿은 즉시 무효가 된다. */
    public void replaceSecretHash(String hash) {
        this.clientSecretHash = hash;
    }

    public void deactivate() {
        this.isActive = false;
    }
}

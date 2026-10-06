package com.hunnit_beasts.auth.domain.oauth.service;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.domain.oauth.dto.OAuthClientCreateRequest;
import com.hunnit_beasts.auth.domain.oauth.dto.OAuthClientResponse;
import com.hunnit_beasts.auth.domain.oauth.entity.OAuthClient;
import com.hunnit_beasts.auth.domain.oauth.repository.OAuthClientRepository;
import com.hunnit_beasts.auth.infrastructure.guard.GuardClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** OAuth 클라이언트 관리(관리자 전용). 최종 인가는 Doro Guard(system:doro#admin)에 위임한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class OAuthClientAdminService {

    /** 서버가 client_id 를 생성할 때 쓰는 난수 바이트 수(base64url 32자) */
    private static final int GENERATED_CLIENT_ID_BYTES = 24;

    private final OAuthClientRepository clientRepository;
    private final GuardClient guardClient;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public OAuthClientResponse create(UUID adminId, OAuthClientCreateRequest request) {
        requireAdmin(adminId);

        if (request == null || request.name() == null) {
            throw new AuthException(ErrorCode.INVALID_INPUT, "name 은 필수입니다.");
        }
        String name = request.name().trim();
        if (name.isEmpty() || name.length() > OAuth2Constants.MAX_CLIENT_NAME_LENGTH) {
            throw new AuthException(ErrorCode.INVALID_INPUT, "name 이 올바르지 않습니다.");
        }
        List<String> redirectUris = validateRedirectUris(request.redirectUris());
        Set<String> scopes = validateScopes(request.scopes());

        String clientId = request.clientId() == null || request.clientId().isBlank()
                ? generateClientId()
                : request.clientId();
        if (!OAuth2Constants.CLIENT_ID_PATTERN.matcher(clientId).matches()) {
            throw new AuthException(ErrorCode.INVALID_INPUT, "clientId 형식이 올바르지 않습니다.");
        }
        if (clientRepository.existsByClientId(clientId)) {
            throw new AuthException(ErrorCode.OAUTH_CLIENT_ALREADY_EXISTS);
        }

        OAuthClient saved = clientRepository.save(OAuthClient.builder()
                .clientId(clientId)
                .name(name)
                .redirectUris(redirectUris)
                .allowedScopes(scopes)
                .firstParty(Boolean.TRUE.equals(request.firstParty()))
                .build());
        log.info("OAuth client registered: clientId={}, firstParty={}, redirectUriCount={}, adminId={}",
                saved.getClientId(), saved.isFirstParty(), redirectUris.size(), adminId);
        return OAuthClientResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<OAuthClientResponse> list(UUID adminId) {
        requireAdmin(adminId);
        return clientRepository.findAllByOrderByCreatedAtDesc().stream().map(OAuthClientResponse::from).toList();
    }

    /** 소프트 삭제: is_active=false. 이후 해당 client_id 의 인가/토큰 요청은 모든 모드에서 거부된다. */
    @Transactional
    public void deactivate(UUID adminId, String clientId) {
        requireAdmin(adminId);
        OAuthClient client = clientRepository.findByClientId(clientId)
                .orElseThrow(() -> new AuthException(ErrorCode.OAUTH_CLIENT_NOT_FOUND));
        client.deactivate();
        log.info("OAuth client deactivated: clientId={}, adminId={}", clientId, adminId);
    }

    private void requireAdmin(UUID adminId) {
        // JWT 역할 클레임은 토큰 수명만큼 낡을 수 있으므로 최종 인가는 Guard 에 위임한다.
        if (adminId == null || !guardClient.check("system", "doro", "admin", adminId.toString())) {
            throw new AuthException(ErrorCode.ACCESS_DENIED);
        }
    }

    private static List<String> validateRedirectUris(List<String> redirectUris) {
        if (redirectUris == null || redirectUris.isEmpty()) {
            throw new AuthException(ErrorCode.INVALID_INPUT, "redirectUris 는 1개 이상 필요합니다.");
        }
        if (redirectUris.size() > OAuth2Constants.MAX_REDIRECT_URIS_PER_CLIENT) {
            throw new AuthException(ErrorCode.INVALID_INPUT,
                    "redirectUris 는 최대 " + OAuth2Constants.MAX_REDIRECT_URIS_PER_CLIENT + "개까지 허용됩니다.");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String uri : redirectUris) {
            RedirectUriValidator.validate(uri).ifPresent(reason -> {
                throw new AuthException(ErrorCode.INVALID_INPUT, reason);
            });
            if (!unique.add(uri)) {
                throw new AuthException(ErrorCode.INVALID_INPUT, "redirectUris 에 중복된 항목이 있습니다.");
            }
        }
        return List.copyOf(unique);
    }

    private static Set<String> validateScopes(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return new LinkedHashSet<>(OAuth2Constants.SUPPORTED_SCOPES);
        }
        Set<String> result = new LinkedHashSet<>(scopes);
        if (!OAuth2Constants.SUPPORTED_SCOPES.containsAll(result)) {
            throw new AuthException(ErrorCode.INVALID_INPUT,
                    "지원하지 않는 scope 입니다. 허용 값: " + String.join(", ", OAuth2Constants.SUPPORTED_SCOPES));
        }
        return result;
    }

    private String generateClientId() {
        byte[] bytes = new byte[GENERATED_CLIENT_ID_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

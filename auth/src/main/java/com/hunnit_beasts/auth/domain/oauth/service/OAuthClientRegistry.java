package com.hunnit_beasts.auth.domain.oauth.service;

import com.hunnit_beasts.auth.common.log.RateLimitedLogGate;
import com.hunnit_beasts.auth.domain.oauth.entity.OAuthClient;
import com.hunnit_beasts.auth.domain.oauth.repository.OAuthClientRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 인가/토큰 요청의 client_id, redirect_uri, scope 를 클라이언트 레지스트리 정책({@link ClientRegistryMode})으로 판정한다.
 * redirect_uri 는 항상 문자열 정확 일치만 인정한다(정규화·부분 일치·와일드카드 없음).
 */
@Slf4j
@Service
public class OAuthClientRegistry {

    /** 판정 결과. allowedScopes 는 해당 클라이언트가 요청할 수 있는 스코프 상한. */
    public record ResolvedClient(String clientId, Set<String> allowedScopes, boolean registered, List<String> redirectUris) {}

    private final OAuthClientRepository clientRepository;
    private final RateLimitedLogGate logGate;
    private final ClientRegistryMode mode;
    /** 환경변수 허용 목록(전역). 등록된 활성 클라이언트에는 적용하지 않는다. */
    private final List<String> allowedRedirectUris;

    public OAuthClientRegistry(
            OAuthClientRepository clientRepository,
            RateLimitedLogGate logGate,
            @Value("${doro.oauth.client-registry-mode:ENFORCE}") String mode,
            @Value("${doro.oauth.allowed-redirect-uris:}") List<String> allowedRedirectUris) {
        this.clientRepository = clientRepository;
        this.logGate = logGate;
        this.mode = ClientRegistryMode.parse(mode);
        this.allowedRedirectUris = allowedRedirectUris.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        log.info("OAuth client registry mode: {}", this.mode);
    }

    public ClientRegistryMode mode() {
        return mode;
    }

    /** 동의 화면에 보여 줄 클라이언트 정보. 등록되고 활성인 클라이언트만 돌려준다(미등록 client_id 의 존재 여부는 알리지 않는다). */
    public record ClientInfo(String clientId, String name, boolean firstParty) {}

    public Optional<ClientInfo> describe(String clientId) {
        if (clientId == null || !OAuth2Constants.CLIENT_ID_PATTERN.matcher(clientId).matches()) {
            return Optional.empty();
        }
        return clientRepository.findByClientId(clientId)
                .filter(OAuthClient::isActive)
                .map(client -> new ClientInfo(client.getClientId(), client.getName(), client.isFirstParty()));
    }

    /** 인가 요청 단계: client_id 와 redirect_uri 를 함께 검증한다. 실패 시 절대 redirect_uri 로 리다이렉트하면 안 된다. */
    public ResolvedClient resolveForAuthorization(String clientId, String redirectUri) {
        requireClientIdFormat(clientId);
        if (redirectUri == null || redirectUri.isBlank()) {
            throw new OAuth2Exception(OAuth2ErrorType.INVALID_REQUEST, "redirect_uri 는 필수입니다.");
        }
        ResolvedClient client = lookup(clientId);
        if (client.registered()) {
            if (!client.redirectUris().contains(redirectUri)) {
                log.warn("Rejected authorization request: redirect_uri does not match the registered client");
                throw new OAuth2Exception(OAuth2ErrorType.INVALID_REQUEST, "허용되지 않은 redirect_uri 입니다.");
            }
            return client;
        }
        if (!allowedRedirectUris.contains(redirectUri)) {
            log.warn("Rejected authorization request with a redirect_uri that is not on the allowlist");
            throw new OAuth2Exception(OAuth2ErrorType.INVALID_REQUEST, "허용되지 않은 redirect_uri 입니다.");
        }
        return client;
    }

    /** 토큰 요청 단계: client_id 만 검증한다(redirect_uri 는 인가 코드에 바인딩된 값과 비교한다). */
    public ResolvedClient resolveForToken(String clientId) {
        requireClientIdFormat(clientId);
        return lookup(clientId);
    }

    /**
     * 요청 스코프 문자열을 파싱·검증한다. 지원하는 스코프의 부분집합이어야 하고, 등록 클라이언트라면 그 클라이언트의 allowed_scopes 의
     * 부분집합이어야 한다.
     */
    public Set<String> resolveScopes(ResolvedClient client, String scope) {
        Set<String> requested = parseScope(scope);
        if (!OAuth2Constants.SUPPORTED_SCOPES.containsAll(requested)) {
            throw new OAuth2Exception(OAuth2ErrorType.INVALID_SCOPE, "지원하지 않는 scope 입니다.");
        }
        if (client.registered() && !client.allowedScopes().containsAll(requested)) {
            throw new OAuth2Exception(OAuth2ErrorType.INVALID_SCOPE, "클라이언트에 허용되지 않은 scope 입니다.");
        }
        return requested;
    }

    /** 공백 구분 scope 파싱(순서 유지, 중복 제거). 형식이 잘못되면 invalid_scope. */
    public static Set<String> parseScope(String scope) {
        Set<String> result = new LinkedHashSet<>();
        if (scope == null || scope.isBlank()) {
            return result;
        }
        if (scope.length() > OAuth2Constants.MAX_SCOPE_LENGTH) {
            throw new OAuth2Exception(OAuth2ErrorType.INVALID_SCOPE, "scope 가 너무 깁니다.");
        }
        for (String token : scope.trim().split(" +")) {
            if (!OAuth2Constants.SCOPE_TOKEN_PATTERN.matcher(token).matches()) {
                throw new OAuth2Exception(OAuth2ErrorType.INVALID_SCOPE, "scope 형식이 올바르지 않습니다.");
            }
            result.add(token);
        }
        return result;
    }

    private ResolvedClient lookup(String clientId) {
        if (mode == ClientRegistryMode.OFF) {
            return new ResolvedClient(clientId, Set.copyOf(OAuth2Constants.SUPPORTED_SCOPES), false, List.of());
        }
        Optional<OAuthClient> found = clientRepository.findByClientId(clientId);
        if (found.isPresent()) {
            OAuthClient client = found.get();
            if (!client.isActive()) {
                // 비활성화(폐기)된 클라이언트가 환경변수 허용 목록으로 되살아나지 않게 모든 모드에서 거부한다.
                log.warn("Rejected request from a deactivated OAuth client");
                throw new OAuth2Exception(OAuth2ErrorType.INVALID_CLIENT, "유효하지 않은 클라이언트입니다.");
            }
            return new ResolvedClient(clientId, client.allowedScopeSet(), true, client.redirectUriList());
        }
        if (mode == ClientRegistryMode.ENFORCE) {
            log.warn("Rejected request from an unregistered OAuth client (registry mode ENFORCE)");
            throw new OAuth2Exception(OAuth2ErrorType.INVALID_CLIENT, "등록되지 않은 클라이언트입니다.");
        }
        if (logGate.tryAcquire("oauth-unregistered-client")) {
            log.warn("OAuth request from an unregistered client_id; falling back to the env redirect_uri allowlist "
                    + "(registry mode WARN). Register the client or set DORO_OAUTH_CLIENT_REGISTRY_MODE=ENFORCE.");
        }
        return new ResolvedClient(clientId, Set.copyOf(OAuth2Constants.SUPPORTED_SCOPES), false, List.of());
    }

    private static void requireClientIdFormat(String clientId) {
        if (clientId == null || !OAuth2Constants.CLIENT_ID_PATTERN.matcher(clientId).matches()) {
            throw new OAuth2Exception(OAuth2ErrorType.INVALID_REQUEST, "client_id 가 없거나 형식이 올바르지 않습니다.");
        }
    }
}

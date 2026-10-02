package com.hunnit_beasts.guard.config;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Guard 호출자(auth, 서브 서비스)를 식별하는 서비스 토큰 설정.
 * OFF: 검사하지 않음(기본) / WARN: 실패를 로그로만 남기고 통과 / ENFORCE: 실패 시 거부.
 * 호출자 모두가 토큰을 보내도록 배포한 뒤 OFF -> WARN -> ENFORCE 순서로 올린다.
 *
 * <p>토큰은 두 종류를 받는다.
 * <ul>
 *   <li>{@code service-token}: 모든 호출자가 같이 쓰는 공유 토큰 (이전 방식, 전환 기간에만 둔다)</li>
 *   <li>{@code service-tokens}: {@code auth:토큰,blog:토큰:schema-write} 형식의 호출자별 토큰. 하나가 유출돼도
 *       그 호출자의 토큰만 교체하면 되고, 어느 호출자의 요청인지 식별할 수 있다.</li>
 * </ul>
 *
 * <p>호출자별 토큰에는 선택적으로 권한(scope)을 붙인다({@code 이름:토큰:권한+권한}). 권한이 없으면 조회/체크/튜플 쓰기만 되고,
 * 활성 스키마 전체를 교체하는 {@code POST /api/v1/guard/schema} 는 {@code schema-write} 가 있는 호출자만 쓸 수 있다.
 * 공유 토큰은 이전 방식과의 호환을 위해 모든 권한을 갖는다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "doro.guard.security")
public class ServiceAuthProperties {

    public enum Mode { OFF, WARN, ENFORCE }

    public static final String HEADER_NAME = "X-Doro-Service-Token";
    /** 공유 토큰으로 인증된 호출자에 붙이는 이름. */
    public static final String SHARED_CALLER = "shared";

    /** 운영 스크립트(split-guard-tokens.sh)가 이 문구로 공유 토큰 사용 여부를 확인한다. 바꾸면 스크립트도 바꿔야 한다. */
    public static final String SHARED_TOKEN_WARNING = "Guard call with the deprecated shared service token";

    /** 활성 스키마를 통째로 교체할 수 있는 권한. 잘못 호출하면 다른 서비스와 IAM 의 타입이 사라진다. */
    public static final String SCOPE_SCHEMA_WRITE = "schema-write";
    private static final Set<String> KNOWN_SCOPES = Set.of(SCOPE_SCHEMA_WRITE);

    static final int MIN_CALLER_TOKEN_LENGTH = 32;
    private static final Pattern CALLER_NAME = Pattern.compile("[a-z][a-z0-9-]{0,31}");

    private Mode mode = Mode.OFF;
    private String serviceToken = "";
    @Setter(AccessLevel.NONE)
    private Map<String, String> callerTokens = Map.of();
    @Setter(AccessLevel.NONE)
    private Map<String, Set<String>> callerScopes = Map.of();

    public boolean isActive() {
        return mode != Mode.OFF;
    }

    /**
     * {@code name:token,name:token} 을 읽는다. 형식이 틀리거나 토큰이 짧거나 겹치면 기동 시점에 실패시켜,
     * 잘못된 설정으로 조용히 인증이 약해지는 일을 막는다.
     */
    public void setServiceTokens(String spec) {
        Map<String, String> parsed = new LinkedHashMap<>();
        Map<String, Set<String>> scopes = new LinkedHashMap<>();
        if (spec != null && !spec.isBlank()) {
            for (String entry : spec.split(",")) {
                String[] parts = entry.split(":", 3);
                String name = parts[0].trim();
                String token = parts.length > 1 ? parts[1].trim() : "";
                Set<String> callerScope = parts.length > 2 ? parseScopes(name, parts[2]) : Set.of();
                if (!CALLER_NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("service-tokens: invalid caller name (expected name:token)");
                }
                if (token.length() < MIN_CALLER_TOKEN_LENGTH) {
                    throw new IllegalArgumentException("service-tokens: token for '" + name + "' is shorter than "
                            + MIN_CALLER_TOKEN_LENGTH + " characters");
                }
                if (SHARED_CALLER.equals(name) || parsed.put(name, token) != null) {
                    throw new IllegalArgumentException("service-tokens: caller '" + name + "' is reserved or duplicated");
                }
                scopes.put(name, callerScope);
            }
            if (parsed.values().stream().distinct().count() != parsed.size()) {
                throw new IllegalArgumentException("service-tokens: callers must not share a token");
            }
        }
        this.callerTokens = Map.copyOf(parsed);
        this.callerScopes = Map.copyOf(scopes);
    }

    private static Set<String> parseScopes(String caller, String spec) {
        Set<String> result = new LinkedHashSet<>();
        for (String scope : spec.split("\\+")) {
            String trimmed = scope.trim();
            if (!KNOWN_SCOPES.contains(trimmed)) {
                throw new IllegalArgumentException("service-tokens: unknown scope for '" + caller + "' (known: " + KNOWN_SCOPES + ")");
            }
            result.add(trimmed);
        }
        return Set.copyOf(result);
    }

    /** 호출자가 권한을 갖는지. 공유 토큰은 모든 권한을 갖고, 호출자별 토큰은 명시된 권한만 갖는다. */
    public boolean hasScope(String caller, String scope) {
        if (SHARED_CALLER.equals(caller)) {
            return true;
        }
        return callerScopes.getOrDefault(caller, Set.of()).contains(scope);
    }

    /** 제시된 토큰의 호출자 이름. 어떤 토큰과도 맞지 않으면 비어 있다. 모든 후보와 상수 시간으로 비교한다. */
    public Optional<String> authenticate(String presented) {
        if (presented == null) {
            return Optional.empty();
        }
        String caller = null;
        if (serviceToken != null && !serviceToken.isEmpty() && sameToken(serviceToken, presented)) {
            caller = SHARED_CALLER;
        }
        for (Map.Entry<String, String> entry : callerTokens.entrySet()) {
            if (sameToken(entry.getValue(), presented)) {
                caller = entry.getKey();
            }
        }
        return Optional.ofNullable(caller);
    }

    /** 호출자별 토큰이 설정된 뒤에도 공유 토큰으로 들어오는 호출은 정리 대상이다. */
    public boolean isSharedTokenDeprecated(String caller) {
        return SHARED_CALLER.equals(caller) && !callerTokens.isEmpty();
    }

    public boolean matches(String presented) {
        return authenticate(presented).isPresent();
    }

    private static boolean sameToken(String expected, String presented) {
        return java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                presented.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}

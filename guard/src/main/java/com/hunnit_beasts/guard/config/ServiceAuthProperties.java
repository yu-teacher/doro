package com.hunnit_beasts.guard.config;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
 * <p>호출자별 토큰에는 선택적으로 권한(scope)과 네임스페이스를 붙인다({@code 이름:토큰:권한+권한:네임스페이스+네임스페이스}). 권한이 없으면 조회/체크/튜플 쓰기만 되고,
 * 활성 스키마 전체를 교체하는 {@code POST /api/v1/guard/schema} 는 {@code schema-write} 가 있는 호출자만 쓸 수 있다.
 * 공유 토큰은 이전 방식과의 호환을 위해 모든 권한을 갖는다.
 *
 * <p>네임스페이스를 지정한 호출자는 그 네임스페이스 객체의 튜플만 쓰고 지울 수 있고, 스키마도 그 네임스페이스의 타입만 바꿀 수 있다.
 * 하나가 유출되거나 버그가 나도 다른 서비스와 IAM 의 권한 데이터({@code system}, {@code user} 등)를 건드리지 못한다.
 * 네임스페이스는 정확한 이름({@code user}) 또는 접두사({@code blog_*})이고, 호출자끼리 겹칠 수 없다.
 * 네임스페이스가 없는 호출자(IAM 등)는 제한이 없다. 조회·체크는 제한하지 않는다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "doro.guard.security")
public class ServiceAuthProperties {

    public enum Mode { OFF, WARN, ENFORCE }

    public static final String HEADER_NAME = "X-Doro-Service-Token";
    /** 인증된 호출자 이름을 REST 요청에 실어 컨트롤러로 넘기는 속성 이름. */
    public static final String CALLER_ATTRIBUTE = "doro.guard.caller";
    /** 공유 토큰으로 인증된 호출자에 붙이는 이름. */
    public static final String SHARED_CALLER = "shared";

    /** 운영 스크립트(split-guard-tokens.sh)가 이 문구로 공유 토큰 사용 여부를 확인한다. 바꾸면 스크립트도 바꿔야 한다. */
    public static final String SHARED_TOKEN_WARNING = "Guard call with the deprecated shared service token";

    /** 활성 스키마를 통째로 교체할 수 있는 권한. 잘못 호출하면 다른 서비스와 IAM 의 타입이 사라진다. */
    public static final String SCOPE_SCHEMA_WRITE = "schema-write";
    private static final Set<String> KNOWN_SCOPES = Set.of(SCOPE_SCHEMA_WRITE);

    static final int MIN_CALLER_TOKEN_LENGTH = 32;
    private static final Pattern CALLER_NAME = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    /** 정확한 이름 또는 접두사(끝의 *). 맨 앞 글자가 필요해서 모든 네임스페이스를 뜻하는 단독 * 는 쓸 수 없다. */
    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("[a-z][a-z0-9_]{0,62}\\*?");

    private Mode mode = Mode.OFF;
    private String serviceToken = "";
    @Setter(AccessLevel.NONE)
    private Map<String, String> callerTokens = Map.of();
    @Setter(AccessLevel.NONE)
    private Map<String, Set<String>> callerScopes = Map.of();
    @Setter(AccessLevel.NONE)
    private Map<String, List<String>> callerNamespaces = Map.of();

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
        Map<String, List<String>> namespaces = new LinkedHashMap<>();
        if (spec != null && !spec.isBlank()) {
            for (String entry : spec.split(",")) {
                String[] parts = entry.split(":", 4);
                String name = parts[0].trim();
                String token = parts.length > 1 ? parts[1].trim() : "";
                Set<String> callerScope = parts.length > 2 ? parseScopes(name, parts[2], parts.length > 3) : Set.of();
                List<String> callerNamespace = parts.length > 3 ? parseNamespaces(name, parts[3]) : List.of();
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
                if (!callerNamespace.isEmpty()) {
                    namespaces.put(name, callerNamespace);
                }
            }
            requireDisjointNamespaces(namespaces);
            if (parsed.values().stream().distinct().count() != parsed.size()) {
                throw new IllegalArgumentException("service-tokens: callers must not share a token");
            }
        }
        this.callerTokens = Map.copyOf(parsed);
        this.callerScopes = Map.copyOf(scopes);
        this.callerNamespaces = Map.copyOf(namespaces);
    }

    private static List<String> parseNamespaces(String caller, String spec) {
        List<String> result = new java.util.ArrayList<>();
        if (spec.isBlank()) {
            return result;
        }
        for (String namespace : spec.split("\\+", -1)) {
            String trimmed = namespace.trim();
            if (!NAMESPACE_PATTERN.matcher(trimmed).matches() || result.contains(trimmed)) {
                throw new IllegalArgumentException("service-tokens: invalid or duplicated namespace for '" + caller
                        + "' (expected name or prefix*)");
            }
            result.add(trimmed);
        }
        return List.copyOf(result);
    }

    /** 두 호출자의 네임스페이스가 겹치면(같거나 한쪽이 다른 쪽의 접두사) 누가 소유자인지 모호해지므로 기동 시점에 거부한다. */
    private static void requireDisjointNamespaces(Map<String, List<String>> namespaces) {
        List<Map.Entry<String, String>> all = new java.util.ArrayList<>();
        namespaces.forEach((caller, list) -> list.forEach(pattern -> all.add(Map.entry(caller, pattern))));
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                if (all.get(i).getKey().equals(all.get(j).getKey())) {
                    continue;
                }
                if (patternsOverlap(all.get(i).getValue(), all.get(j).getValue())) {
                    throw new IllegalArgumentException("service-tokens: namespaces of '" + all.get(i).getKey() + "' and '"
                            + all.get(j).getKey() + "' overlap");
                }
            }
        }
    }

    private static boolean patternsOverlap(String a, String b) {
        boolean aPrefix = a.endsWith("*");
        boolean bPrefix = b.endsWith("*");
        String aBase = aPrefix ? a.substring(0, a.length() - 1) : a;
        String bBase = bPrefix ? b.substring(0, b.length() - 1) : b;
        if (aPrefix && bPrefix) {
            return aBase.startsWith(bBase) || bBase.startsWith(aBase);
        }
        if (aPrefix) {
            return bBase.startsWith(aBase);
        }
        if (bPrefix) {
            return aBase.startsWith(bBase);
        }
        return aBase.equals(bBase);
    }

    /** 권한 칸을 비우는 것은 뒤에 네임스페이스가 올 때만 허용한다({@code 이름:토큰::네임스페이스}). 끝의 빈 칸은 오타일 가능성이 크다. */
    private static Set<String> parseScopes(String caller, String spec, boolean namespaceFollows) {
        Set<String> result = new LinkedHashSet<>();
        if (spec.isBlank() && namespaceFollows) {
            return Set.of();
        }
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

    /** 호출자의 네임스페이스가 제한돼 있는지. 공유 토큰과 네임스페이스를 지정하지 않은 호출자(IAM 등)는 제한이 없다. */
    public boolean isNamespaceRestricted(String caller) {
        return caller != null && callerNamespaces.containsKey(caller);
    }

    /** 호출자가 이 네임스페이스를 소유하는지. 제한이 없는 호출자는 모두 소유한다. */
    public boolean ownsNamespace(String caller, String namespace) {
        List<String> patterns = callerNamespaces.get(caller);
        if (patterns == null) {
            return true;
        }
        for (String pattern : patterns) {
            if (pattern.endsWith("*") ? namespace.startsWith(pattern.substring(0, pattern.length() - 1)) : namespace.equals(pattern)) {
                return true;
            }
        }
        return false;
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

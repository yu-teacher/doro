package com.hunnit_beasts.auth.domain.oauth.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 클라이언트 등록 시 redirect_uri 형식 검증. 절대 URI 이고 https 만 허용하되 loopback 호스트에는 http 도 허용한다.
 * fragment, userinfo, 와일드카드, 경로 탐색(. / ..) 세그먼트는 허용하지 않는다.
 */
public final class RedirectUriValidator {

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private RedirectUriValidator() {
    }

    /** @return 오류 설명(유효하면 비어 있음). 설명에는 입력 원문을 포함하지 않는다. */
    public static Optional<String> validate(String uriText) {
        if (uriText == null || uriText.isBlank()) {
            return Optional.of("redirect_uri 가 비어 있습니다.");
        }
        if (uriText.length() > OAuth2Constants.MAX_REDIRECT_URI_LENGTH) {
            return Optional.of("redirect_uri 는 최대 " + OAuth2Constants.MAX_REDIRECT_URI_LENGTH + "자까지 허용됩니다.");
        }
        if (!uriText.equals(uriText.strip()) || uriText.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            return Optional.of("redirect_uri 에 공백/제어 문자를 포함할 수 없습니다.");
        }
        if (uriText.indexOf('*') >= 0) {
            return Optional.of("redirect_uri 에 와일드카드(*)를 사용할 수 없습니다.");
        }
        if (uriText.indexOf('#') >= 0) {
            return Optional.of("redirect_uri 에 fragment(#)를 포함할 수 없습니다.");
        }
        if (uriText.indexOf('\\') >= 0) {
            return Optional.of("redirect_uri 에 역슬래시를 포함할 수 없습니다.");
        }
        URI uri;
        try {
            uri = new URI(uriText);
        } catch (URISyntaxException e) {
            return Optional.of("redirect_uri 형식이 올바르지 않습니다.");
        }
        if (!uri.isAbsolute() || uri.getScheme() == null) {
            return Optional.of("redirect_uri 는 절대 URI 여야 합니다.");
        }
        if (uri.getRawUserInfo() != null || (uri.getRawAuthority() != null && uri.getRawAuthority().indexOf('@') >= 0)) {
            return Optional.of("redirect_uri 에 사용자 정보(userinfo)를 포함할 수 없습니다.");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return Optional.of("redirect_uri 에 호스트가 필요합니다.");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        boolean loopback = LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT));
        if (!"https".equals(scheme) && !("http".equals(scheme) && loopback)) {
            return Optional.of("redirect_uri 는 https 만 허용됩니다(loopback 호스트는 http 허용).");
        }
        String rawPath = uri.getRawPath();
        if (rawPath != null) {
            for (String segment : rawPath.split("/", -1)) {
                String lower = segment.toLowerCase(Locale.ROOT);
                if (".".equals(segment) || "..".equals(segment) || lower.contains("%2e") || lower.contains("%2f")) {
                    return Optional.of("redirect_uri 경로에 상대 경로 세그먼트를 사용할 수 없습니다.");
                }
            }
        }
        return Optional.empty();
    }
}

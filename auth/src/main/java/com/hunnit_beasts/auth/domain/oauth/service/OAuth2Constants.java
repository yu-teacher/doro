package com.hunnit_beasts.auth.domain.oauth.service;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** OAuth 2.1 / OIDC 처리에 쓰는 프로토콜 상수. */
public final class OAuth2Constants {

    private OAuth2Constants() {
    }

    public static final String SCOPE_OPENID = "openid";
    public static final String SCOPE_PROFILE = "profile";
    public static final String SCOPE_EMAIL = "email";
    /** 지원하는 스코프(표시 순서 유지). 클라이언트 allowed_scopes 는 이 집합의 부분집합이어야 한다. */
    public static final List<String> SUPPORTED_SCOPES = List.of(SCOPE_OPENID, SCOPE_PROFILE, SCOPE_EMAIL);
    /** 클라이언트 등록 시 scopes 를 생략하면 부여하는 기본 스코프 */
    public static final Set<String> DEFAULT_CLIENT_SCOPES = Set.copyOf(SUPPORTED_SCOPES);

    public static final String GRANT_AUTHORIZATION_CODE = "authorization_code";
    public static final String GRANT_REFRESH_TOKEN = "refresh_token";
    public static final String RESPONSE_TYPE_CODE = "code";
    public static final String PKCE_METHOD_S256 = "S256";

    /** OAuth 세션을 식별하는 IP 마커. 실제 IP 로는 나올 수 없는 값이라 일반 로그인 세션과 섞이지 않는다. */
    public static final String SESSION_IP_MARKER = "OAuth2";
    /** 클라이언트별 세션 중복 제거 및 리프레시 토큰 클라이언트 바인딩에 쓰는 User-Agent 마커 접두어 */
    public static final String SESSION_USER_AGENT_PREFIX = "OAuth2 PKCE Flow: ";
    public static final String SESSION_DEVICE_PREFIX = "OAuth2 Client: ";

    /** client_id 허용 형식(길이 상한은 oauth_clients.client_id 와 user_sessions.device_info 길이에 맞춘다). */
    public static final Pattern CLIENT_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._~-]{1,100}$");
    /** RFC 7636 S256 code_challenge: base64url 43자 */
    public static final Pattern CODE_CHALLENGE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    /** RFC 7636 code_verifier: unreserved 문자 43~128자 */
    public static final Pattern CODE_VERIFIER_PATTERN = Pattern.compile("^[A-Za-z0-9._~-]{43,128}$");
    /** scope-token (RFC 6749 §3.3) */
    public static final Pattern SCOPE_TOKEN_PATTERN = Pattern.compile("^[\\x21\\x23-\\x5B\\x5D-\\x7E]+$");

    public static final int MAX_STATE_LENGTH = 512;
    public static final int MAX_NONCE_LENGTH = 256;
    public static final int MAX_SCOPE_LENGTH = 200;
    public static final int MAX_REDIRECT_URI_LENGTH = 500;
    public static final int MAX_REDIRECT_URIS_PER_CLIENT = 10;
    public static final int MAX_CLIENT_NAME_LENGTH = 100;

    /** 토큰 응답 캐시 금지 헤더(RFC 6749 §5.1) */
    public static final String CACHE_CONTROL_NO_STORE = "no-store";
    public static final String PRAGMA_NO_CACHE = "no-cache";
}

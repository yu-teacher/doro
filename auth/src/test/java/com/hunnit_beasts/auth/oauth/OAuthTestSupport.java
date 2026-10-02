package com.hunnit_beasts.auth.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunnit_beasts.auth.domain.oauth.entity.OAuthClient;
import com.hunnit_beasts.auth.domain.oauth.repository.OAuthClientRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** OAuth 통합 테스트 공용 도우미. 구체 테스트 클래스가 @SpringBootTest/@AutoConfigureMockMvc/@ActiveProfiles("test") 를 선언한다. */
abstract class OAuthTestSupport {

    static final String PASSWORD = "Password123!";
    static final String GOOD_REDIRECT = "https://good.example/cb";

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected JdbcTemplate jdbcTemplate;
    @Autowired
    protected OAuthClientRepository clientRepository;

    protected final ObjectMapper objectMapper = new ObjectMapper();

    record Login(String email, String accessToken, String refreshToken, String sessionId, String userId) {}

    record Pkce(String verifier, String challenge) {}

    static Pkce newPkce() throws Exception {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        return new Pkce(verifier, challenge);
    }

    protected String uniqueClientId() {
        return "client-" + UUID.randomUUID();
    }

    protected OAuthClient registerClient(String clientId, List<String> redirectUris, String... scopes) {
        Set<String> scopeSet = new LinkedHashSet<>(scopes.length == 0 ? List.of("openid", "profile", "email") : List.of(scopes));
        return clientRepository.save(OAuthClient.builder()
                .clientId(clientId).name("Test App").redirectUris(redirectUris).allowedScopes(scopeSet).build());
    }

    protected Login signupAndLogin() throws Exception {
        String email = "oauth-" + UUID.randomUUID() + "@doro.local";
        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"name\":\"OAuth Tester\"}"));
        return login(email);
    }

    protected Login login(String email) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"deviceInfo\":\"test\"}"))
                .andReturn().getResponse().getContentAsString();
        JsonNode tokens = objectMapper.readTree(body).path("data").path("tokens");
        String accessToken = tokens.path("accessToken").asText();
        String userId = jdbcTemplate.queryForObject("select id from users where email = ?", String.class, email);
        return new Login(email, accessToken, tokens.path("refreshToken").asText(), tokens.path("sessionId").asText(), userId);
    }

    /** Bearer 로 /oauth2/authorize 를 호출하는 요청 빌더(포털 동의 화면의 fetch 와 동일). */
    protected MockHttpServletRequestBuilder authorizeRequest(Login login, String clientId, String redirectUri, String challenge) {
        return get("/oauth2/authorize")
                .header("Authorization", "Bearer " + login.accessToken())
                .param("client_id", clientId).param("redirect_uri", redirectUri)
                .param("response_type", "code").param("code_challenge", challenge);
    }

    /** 인가 코드를 발급받아 반환(오류면 빈 문자열). */
    protected String authorizeCode(Login login, String clientId, String redirectUri, Pkce pkce, String scope, String nonce) throws Exception {
        MockHttpServletRequestBuilder request = authorizeRequest(login, clientId, redirectUri, pkce.challenge())
                .param("state", "st-1");
        if (scope != null) {
            request.param("scope", scope);
        }
        if (nonce != null) {
            request.param("nonce", nonce);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("code").asText("");
    }

    protected MockHttpServletRequestBuilder tokenForm(String... keyValues) {
        MockHttpServletRequestBuilder request = post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED);
        for (int i = 0; i < keyValues.length; i += 2) {
            request.param(keyValues[i], keyValues[i + 1]);
        }
        return request;
    }

    protected MockHttpServletRequestBuilder codeExchangeForm(String code, String clientId, String redirectUri, String verifier) {
        return tokenForm("grant_type", "authorization_code", "code", code, "client_id", clientId,
                "redirect_uri", redirectUri, "code_verifier", verifier);
    }

    protected JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** 코드 교환(폼) 성공 응답 본문 */
    protected JsonNode exchange(Login login, String clientId, String redirectUri, String scope, String nonce) throws Exception {
        Pkce pkce = newPkce();
        String code = authorizeCode(login, clientId, redirectUri, pkce, scope, nonce);
        MvcResult result = mockMvc.perform(codeExchangeForm(code, clientId, redirectUri, pkce.verifier())).andReturn();
        return json(result);
    }

    static final String ISSUER = "https://auth.doro.local";

    /** JWKS 엔드포인트에서 kid 에 맞는 공개키를 직접 복원한다(서버 내부 키 객체를 쓰지 않고 게시된 키로 검증). */
    protected RSAPublicKey publishedKey(String kid) throws Exception {
        String body = mockMvc.perform(get("/.well-known/jwks.json")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (JsonNode jwk : objectMapper.readTree(body).path("keys")) {
            if (kid.equals(jwk.path("kid").asText())) {
                Base64.Decoder dec = Base64.getUrlDecoder();
                BigInteger n = new BigInteger(1, dec.decode(jwk.path("n").asText()));
                BigInteger e = new BigInteger(1, dec.decode(jwk.path("e").asText()));
                return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
            }
        }
        throw new AssertionError("kid not published in JWKS");
    }

    protected Jws<Claims> verifyIdToken(String idToken, String clientId) throws Exception {
        String headerJson = new String(Base64.getUrlDecoder().decode(idToken.split("\\.")[0]));
        JsonNode header = objectMapper.readTree(headerJson);
        assertThat(header.path("alg").asText()).isEqualTo("RS256");
        return Jwts.parser().verifyWith(publishedKey(header.path("kid").asText()))
                .requireIssuer(ISSUER).requireAudience(clientId).build().parseSignedClaims(idToken);
    }

}

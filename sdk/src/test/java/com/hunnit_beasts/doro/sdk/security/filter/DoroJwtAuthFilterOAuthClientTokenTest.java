package com.hunnit_beasts.doro.sdk.security.filter;

import com.hunnit_beasts.doro.sdk.config.DoroProperties.IssuerValidation;
import com.hunnit_beasts.doro.sdk.config.DoroProperties.RevocationCheck;
import com.hunnit_beasts.doro.sdk.domain.DoroUser;
import com.hunnit_beasts.doro.sdk.domain.DoroUserContext;
import com.hunnit_beasts.doro.sdk.security.jwks.JwksKeyProvider;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Date;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** OAuth 클라이언트가 받은 토큰(cid)은 서비스가 허용한 클라이언트의 것만 통과해야 한다. */
class DoroJwtAuthFilterOAuthClientTokenTest {

    private static final String KID = "cid-kid";

    private KeyPair keyPair;
    private JwksKeyProvider keyProvider;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
        keyProvider = new JwksKeyProvider(null);
        keyProvider.registerKey(KID, keyPair.getPublic());
    }

    private String token(String clientId) {
        var builder = Jwts.builder()
                .header().keyId(KID).and()
                .subject(UUID.randomUUID().toString())
                .claim("role", "USER")
                .expiration(new Date(System.currentTimeMillis() + 60_000));
        if (clientId != null) {
            builder.claim("cid", clientId);
        }
        return builder.signWith(keyPair.getPrivate(), Jwts.SIG.RS256).compact();
    }

    private DoroUser run(DoroJwtAuthFilter filter, String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        AtomicReference<DoroUser> ref = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> ref.set(DoroUserContext.getCurrentUser()));
        return ref.get();
    }

    private DoroJwtAuthFilter filter(Set<String> acceptedClients) {
        return new DoroJwtAuthFilter(keyProvider, null, IssuerValidation.OFF, "", 0, "",
                RevocationCheck.OFF, null, true, acceptedClients);
    }

    @Test
    @DisplayName("기본(허용 클라이언트 없음): 일반 로그인 토큰(cid 없음)은 그대로 통과하고 clientId 는 null")
    void plainLoginTokensAreUnaffected() throws Exception {
        DoroUser user = run(filter(Set.of()), token(null));

        assertThat(user.isAuthenticated()).isTrue();
        assertThat(user.clientId()).isNull();
        assertThat(user.isOAuthClientToken()).isFalse();
    }

    @Test
    @DisplayName("기본(허용 클라이언트 없음): OAuth 클라이언트 토큰은 거부된다 (제3자 토큰이 사용자 전권으로 동작하지 못하게)")
    void oauthClientTokensAreRejectedByDefault() throws Exception {
        assertThat(run(filter(Set.of()), token("third-party-app")).isAuthenticated()).isFalse();
    }

    @Test
    @DisplayName("허용 목록에 있는 클라이언트의 토큰만 통과하고, DoroUser 에 clientId 가 담긴다")
    void onlyListedClientsAreAccepted() throws Exception {
        DoroJwtAuthFilter filter = filter(Set.of("doro-blog"));

        DoroUser accepted = run(filter, token("doro-blog"));
        assertThat(accepted.isAuthenticated()).isTrue();
        assertThat(accepted.clientId()).isEqualTo("doro-blog");
        assertThat(accepted.isOAuthClientToken()).isTrue();

        assertThat(run(filter, token("another-app")).isAuthenticated()).as("목록에 없는 다른 클라이언트").isFalse();
    }

    @Test
    @DisplayName("빈 cid 는 일반 토큰으로 본다 (빈 문자열로 허용 검사를 우회하거나 오작동하지 않는다)")
    void blankClientIdIsTreatedAsPlainToken() throws Exception {
        DoroUser user = run(filter(Set.of()), token("  "));

        assertThat(user.isAuthenticated()).isTrue();
        assertThat(user.clientId()).isNull();
    }
}

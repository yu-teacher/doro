package com.hunnit_beasts.doro.sdk.security.filter;

import com.hunnit_beasts.doro.sdk.domain.DoroUser;
import com.hunnit_beasts.doro.sdk.domain.DoroUserContext;
import com.hunnit_beasts.doro.sdk.security.jwks.JwksKeyProvider;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DoroJwtAuthFilterTest {

    @Test
    @DisplayName("로컬 JWKS 토큰 검증 필터: JWT 서명 검증 및 DoroUserContext 스레드로컬 자동 주입/정리 검증")
    void testJwtAuthFilterLifecycle() throws Exception {
        // RSA 키페어 생성
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();
        String kid = "doro-sdk-test-key";

        JwksKeyProvider keyProvider = new JwksKeyProvider(null);
        keyProvider.registerKey(kid, keyPair.getPublic());

        DoroJwtAuthFilter filter = new DoroJwtAuthFilter(keyProvider);

        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        // RSA 비대칭 서명 JWT 생성
        String jwtToken = Jwts.builder()
                .header().keyId(kid).and()
                .subject(userId.toString())
                .claim("email", "subservice.user@doro.local")
                .claim("sid", sessionId.toString())
                .claim("uidx", 1)
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + jwtToken);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<DoroUser> userInsideFilterChain = new AtomicReference<>();
        MockFilterChain filterChain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                userInsideFilterChain.set(DoroUserContext.getCurrentUser());
            }
        };

        filter.doFilter(request, response, filterChain);

        // 필터체인 내부에서 사용자 정보가 올바르게 주입되었는지 검증
        DoroUser authenticatedUser = userInsideFilterChain.get();
        assertThat(authenticatedUser).isNotNull();
        assertThat(authenticatedUser.isAuthenticated()).isTrue();
        assertThat(authenticatedUser.userId()).isEqualTo(userId);
        assertThat(authenticatedUser.email()).isEqualTo("subservice.user@doro.local");
        assertThat(authenticatedUser.sessionId()).isEqualTo(sessionId);
        assertThat(authenticatedUser.userIndex()).isEqualTo(1);

        // 필터 실행 완료 후 스레드로컬이 안전하게 clear 되었는지 검증
        assertThat(DoroUserContext.getCurrentUser().isAuthenticated()).isFalse();
    }
}

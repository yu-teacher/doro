package com.hunnit_beasts.doro.sdk;

import com.hunnit_beasts.doro.sdk.annotation.CurrentDoroUser;
import com.hunnit_beasts.doro.sdk.annotation.DoroGuard;
import com.hunnit_beasts.doro.sdk.client.DoroGuardClient;
import com.hunnit_beasts.doro.sdk.config.DoroAutoConfiguration;
import com.hunnit_beasts.doro.sdk.domain.DoroUser;
import com.hunnit_beasts.doro.sdk.exception.DoroAccessDeniedException;
import com.hunnit_beasts.doro.sdk.security.jwks.JwksKeyProvider;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = DoroSdkIntegrationTest.TestAppConfig.class)
@AutoConfigureMockMvc
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "doro.guard.enabled=false"
})
class DoroSdkIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DoroGuardClient mockGuardClient;

    @Autowired
    private JwksKeyProvider jwksKeyProvider;

    private KeyPair keyPair;
    private final String kid = "doro-sdk-integration-key";

    @Configuration
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    @org.springframework.context.annotation.EnableAspectJAutoProxy
    @Import(DoroAutoConfiguration.class)
    static class TestAppConfig {

        @Bean
        @Primary
        public DoroGuardClient doroGuardClient() {
            return Mockito.mock(DoroGuardClient.class);
        }

        @RestController
        static class SampleDocumentController {

            @DoroGuard("document:#docId#viewer")
            @GetMapping("/api/v1/documents/{docId}")
            public ResponseEntity<Map<String, Object>> getDocument(
                    @PathVariable String docId,
                    @CurrentDoroUser DoroUser user
            ) {
                return ResponseEntity.ok(Map.of(
                        "docId", docId,
                        "title", "Secret Design Spec",
                        "accessor", user.email(),
                        "userIndex", user.userIndex()
                ));
            }

            @ExceptionHandler(DoroAccessDeniedException.class)
            public ResponseEntity<Map<String, String>> handleAccessDenied(DoroAccessDeniedException e) {
                return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
            }
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        this.keyPair = keyGen.generateKeyPair();
        jwksKeyProvider.registerKey(kid, keyPair.getPublic());
    }

    @Test
    @DisplayName("SDK 통합 E2E 테스트: 유효한 JWT 전달 + Doro Guard 권한 승인 시 200 OK 및 @CurrentDoroUser 주입 확인")
    void testSdkAuthorizedRequest() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = createTestJwt(userId, "engineer@doro.local", 0);

        when(mockGuardClient.check(eq("document"), eq("doc-123"), eq("viewer"), eq(userId.toString())))
                .thenReturn(true);

        mockMvc.perform(get("/api/v1/documents/doc-123")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.docId").value("doc-123"))
                .andExpect(jsonPath("$.accessor").value("engineer@doro.local"))
                .andExpect(jsonPath("$.userIndex").value(0));
    }

    @Test
    @DisplayName("SDK 통합 E2E 테스트: 유효한 JWT이지만 Doro Guard 권한 거부 시 403 Forbidden 반환")
    void testSdkAccessDeniedRequest() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = createTestJwt(userId, "guest@doro.local", 0);

        when(mockGuardClient.check(eq("document"), eq("doc-999"), eq("viewer"), eq(userId.toString())))
                .thenReturn(false);

        mockMvc.perform(get("/api/v1/documents/doc-999")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").exists());
    }

    private String createTestJwt(UUID userId, String email, int userIndex) {
        return Jwts.builder()
                .header().keyId(kid).and()
                .subject(userId.toString())
                .claim("email", email)
                .claim("sid", UUID.randomUUID().toString())
                .claim("uidx", userIndex)
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }
}

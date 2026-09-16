package com.hunnit_beasts.doro.sdk.e2e;

import com.hunnit_beasts.doro.sdk.annotation.CurrentDoroUser;
import com.hunnit_beasts.doro.sdk.annotation.DoroGuard;
import com.hunnit_beasts.doro.sdk.client.DoroGuardClient;
import com.hunnit_beasts.doro.sdk.config.DoroAutoConfiguration;
import com.hunnit_beasts.doro.sdk.domain.DoroUser;
import com.hunnit_beasts.doro.sdk.exception.DoroAccessDeniedException;
import com.hunnit_beasts.doro.sdk.security.jwks.JwksKeyProvider;
import com.hunnit_beasts.guard.interfaces.grpc.*;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = FullSystemCrossModuleE2eTest.FullSystemTestConfig.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "doro.guard.enabled=false"
})
class FullSystemCrossModuleE2eTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwksKeyProvider jwksKeyProvider;

    private static final Server inProcessGrpcServer;
    private static final ManagedChannel grpcChannel;
    private static final KeyPair iamKeyPair;
    private static final String KID = "iam-key-v1";

    // 인메모리 Zanzibar 튜플 저장소
    private static final Set<String> tupleStore = ConcurrentHashMap.newKeySet();

    static {
        try {
            // 1. Doro IAM 비대칭 RSA 키페어 생성
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048);
            iamKeyPair = keyGen.generateKeyPair();

            // 2. Zanzibar ReBAC gRPC 인프로세스 서버 사전 가동
            String serverName = InProcessServerBuilder.generateName();
            inProcessGrpcServer = InProcessServerBuilder.forName(serverName)
                    .directExecutor()
                    .addService(new GuardServiceGrpc.GuardServiceImplBase() {
                        @Override
                        public void check(CheckRequest request, StreamObserver<CheckResponse> responseObserver) {
                            String directKey = request.getNamespace() + ":" + request.getObjectId() + "#" +
                                    request.getRelation() + "@" + request.getSubjectNamespace() + ":" + request.getSubjectId();

                            boolean allowed = tupleStore.contains(directKey);

                            // TTU 폴더 상속 검사
                            if (!allowed && "document".equals(request.getNamespace()) && "viewer".equals(request.getRelation())) {
                                for (String tuple : tupleStore) {
                                    if (tuple.startsWith("document:" + request.getObjectId() + "#parent@folder:")) {
                                        String folderId = tuple.substring(tuple.lastIndexOf("folder:") + 7);
                                        String folderKey = "folder:" + folderId + "#viewer@user:" + request.getSubjectId();
                                        if (tupleStore.contains(folderKey)) {
                                            allowed = true;
                                            break;
                                        }
                                    }
                                }
                            }

                            responseObserver.onNext(CheckResponse.newBuilder().setAllowed(allowed).build());
                            responseObserver.onCompleted();
                        }

                        @Override
                        public void writeTuples(WriteTuplesRequest request, StreamObserver<WriteTuplesResponse> responseObserver) {
                            for (RelationTupleProto t : request.getTuplesList()) {
                                tupleStore.add(t.getNamespace() + ":" + t.getObjectId() + "#" + t.getRelation() + "@" +
                                        t.getSubjectNamespace() + ":" + t.getSubjectId());
                            }
                            responseObserver.onNext(WriteTuplesResponse.newBuilder().setWrittenCount(request.getTuplesCount()).build());
                            responseObserver.onCompleted();
                        }
                    })
                    .build()
                    .start();

            grpcChannel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Configuration
    @EnableWebMvc
    @EnableAspectJAutoProxy
    @Import(DoroAutoConfiguration.class)
    static class FullSystemTestConfig {

        @Bean
        @Primary
        public DoroGuardClient doroGuardClient() {
            return new DoroGuardClient(grpcChannel);
        }

        @RestController
        static class EnterpriseDocumentController {

            @DoroGuard("document:#docId#viewer")
            @GetMapping("/api/v1/documents/{docId}")
            public ResponseEntity<Map<String, Object>> getDocument(
                    @PathVariable String docId,
                    @CurrentDoroUser DoroUser user
            ) {
                return ResponseEntity.ok(Map.of(
                        "docId", docId,
                        "status", "SUCCESS",
                        "userId", user.userId().toString(),
                        "userEmail", user.email(),
                        "sessionId", user.sessionId().toString(),
                        "userIndex", user.userIndex()
                ));
            }

            @ExceptionHandler(DoroAccessDeniedException.class)
            public ResponseEntity<Map<String, String>> handleDenied(DoroAccessDeniedException e) {
                return ResponseEntity.status(403).body(Map.of(
                        "error", "FORBIDDEN",
                        "message", e.getMessage()
                ));
            }
        }
    }

    @BeforeEach
    void setUp() {
        jwksKeyProvider.registerKey(KID, iamKeyPair.getPublic());
    }

    @AfterAll
    static void teardown() {
        if (grpcChannel != null) {
            grpcChannel.shutdownNow();
        }
        if (inProcessGrpcServer != null) {
            inProcessGrpcServer.shutdownNow();
        }
    }

    @Test
    @DisplayName("전체 통합 E2E: IAM 토큰 발급 ➡️ SDK 로컬 JWKS 검증 ➡️ Guard gRPC 직접 권한 승인 ➡️ 200 OK")
    void testDirectPermissionFullFlow() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String userEmail = "alice@hunnit-beasts.com";

        // 1. Guard gRPC 저장소에 권한 튜플 등록: document:doc-direct-1#viewer@user:<userId>
        tupleStore.add("document:doc-direct-1#viewer@user:" + userId);

        // 2. IAM 비대칭키 서명 JWT 생성 (Access Token)
        String accessToken = createIamJwt(userId, userEmail, sessionId, 0);

        // 3. 서브 서비스 REST API 호출
        mockMvc.perform(get("/api/v1/documents/doc-direct-1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.docId").value("doc-direct-1"))
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.userEmail").value(userEmail))
                .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.userIndex").value(0));
    }

    @Test
    @DisplayName("전체 통합 E2E: 상위 폴더 권한 보유자 ➡️ TTU 계층 상속으로 하위 문서 접근 승인 ➡️ 200 OK")
    void testTtuHierarchicalInheritanceFullFlow() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String userEmail = "bob.manager@hunnit-beasts.com";

        // 1. Guard gRPC에 계층 구조 튜플 등록:
        //    folder:finance-folder#viewer@user:<userId>
        //    document:salary-report#parent@folder:finance-folder
        tupleStore.add("folder:finance-folder#viewer@user:" + userId);
        tupleStore.add("document:salary-report#parent@folder:finance-folder");

        // 2. Bob의 IAM JWT 생성
        String accessToken = createIamJwt(userId, userEmail, sessionId, 1);

        // 3. 상위 폴더 권한을 통한 하위 문서 접근 확인
        mockMvc.perform(get("/api/v1/documents/salary-report")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.docId").value("salary-report"))
                .andExpect(jsonPath("$.userEmail").value(userEmail))
                .andExpect(jsonPath("$.userIndex").value(1));
    }

    @Test
    @DisplayName("전체 통합 E2E: 인증은 되었으나 Guard 권한이 없는 사용자 ➡️ 403 Forbidden 및 차단 메시지 반환")
    void testPermissionDeniedFullFlow() throws Exception {
        UUID intruderId = UUID.randomUUID();
        String accessToken = createIamJwt(intruderId, "intruder@evil.com", UUID.randomUUID(), 0);

        // 권한이 없는 문서 접근
        mockMvc.perform(get("/api/v1/documents/doc-secret-999")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("전체 통합 E2E: 공격자가 서명한 위조 토큰으로 접근 ➡️ 인증 실패 및 403 차단")
    void testTamperedTokenRejectedFullFlow() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair attackerKey = gen.generateKeyPair();

        // 공격자 키로 서명된 위조 토큰
        String fakeToken = Jwts.builder()
                .header().keyId(KID).and()
                .subject(UUID.randomUUID().toString())
                .claim("email", "fake@hacker.com")
                .signWith(attackerKey.getPrivate(), Jwts.SIG.RS256)
                .compact();

        mockMvc.perform(get("/api/v1/documents/doc-direct-1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fakeToken))
                .andExpect(status().isForbidden());
    }

    private String createIamJwt(UUID userId, String email, UUID sessionId, int userIndex) {
        return Jwts.builder()
                .header().keyId(KID).and()
                .subject(userId.toString())
                .claim("email", email)
                .claim("sid", sessionId.toString())
                .claim("uidx", userIndex)
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(iamKeyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }
}

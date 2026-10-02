package com.hunnit_beasts.auth.core.token;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtKeyProviderTest {

    private static final String PRIV_KEY = "doro:iam:jwt:keypair:private";
    private static final String PUB_KEY = "doro:iam:jwt:keypair:public";
    private static final String SECRET = "a-long-random-secret-for-tests";

    /** 맵으로 동작하는 가짜 Redis. */
    private static StringRedisTemplate fakeRedis(Map<String, String> store) {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(inv -> store.get(inv.<String>getArgument(0)));
        doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(ops).set(anyString(), anyString());
        return template;
    }

    private static JwtKeyProvider provider(StringRedisTemplate redis, String kid, String secret) {
        JwtKeyProvider p = new JwtKeyProvider(redis);
        ReflectionTestUtils.setField(p, "keyId", kid);
        ReflectionTestUtils.setField(p, "redisKeyEncryptionSecret", secret);
        return p;
    }

    private static String pem(String header, byte[] der) {
        return "-----BEGIN " + header + "-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der)
                + "\n-----END " + header + "-----";
    }

    private static KeyPair newKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    private static String sign(KeyPair pair, String kid) {
        return Jwts.builder().header().keyId(kid).and()
                .subject(UUID.randomUUID().toString())
                .claim("sid", UUID.randomUUID().toString())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(pair.getPrivate(), Jwts.SIG.RS256).compact();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> keys(JwtKeyProvider p) {
        return (List<Map<String, Object>>) p.getJwks().get("keys");
    }

    // ---------------- A1 ----------------

    @Test
    @DisplayName("A1: JWKS 의 n/e 는 선행 0x00 없이 최소 길이 unsigned big-endian 이며 동일한 공개키로 복원된다")
    void jwksModulusHasNoLeadingZeroAndRoundTrips() throws Exception {
        JwtKeyProvider p = provider(null, "kid-1", "");
        p.init();
        RSAPublicKey pub = p.getPublicKey();
        // 2048비트 모듈러스는 최상위 비트가 항상 1이므로 BigInteger.toByteArray() 는 선행 0x00 을 붙인다 (이전 구현의 버그 조건)
        assertThat(pub.getModulus().toByteArray()[0]).isZero();

        Map<String, Object> jwk = keys(p).get(0);
        byte[] n = Base64.getUrlDecoder().decode((String) jwk.get("n"));
        byte[] e = Base64.getUrlDecoder().decode((String) jwk.get("e"));

        assertThat(n).hasSize(256);
        assertThat(n[0]).isNotZero();
        assertThat(e[0]).isNotZero();
        RSAPublicKey rebuilt = (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(new BigInteger(1, n), new BigInteger(1, e)));
        assertThat(rebuilt).isEqualTo(pub);
    }

    // ---------------- A2(a) ----------------

    @Test
    @DisplayName("A2a: 시크릿이 있으면 Redis 개인키는 enc:v1: 로 암호화 저장되고 재기동 시 동일 키로 복원된다")
    void encryptedRoundTrip() {
        Map<String, String> store = new HashMap<>();
        JwtKeyProvider first = provider(fakeRedis(store), "kid-1", SECRET);
        first.init();

        assertThat(store.get(PRIV_KEY)).startsWith("enc:v1:");
        assertThat(store.get(PRIV_KEY)).doesNotContain(Base64.getEncoder().encodeToString(first.getPrivateKey().getEncoded()).substring(0, 40));

        JwtKeyProvider second = provider(fakeRedis(store), "kid-1", SECRET);
        second.init();
        assertThat(second.getPrivateKey()).isEqualTo(first.getPrivateKey());
        assertThat(second.getPublicKey()).isEqualTo(first.getPublicKey());
    }

    @Test
    @DisplayName("A2a: 같은 키를 두 번 암호화해도 salt/IV 가 달라 결과가 다르다")
    void encryptionUsesRandomSaltAndIv() {
        Map<String, String> storeA = new HashMap<>();
        JwtKeyProvider first = provider(fakeRedis(storeA), "kid-1", SECRET);
        first.init();
        String firstCipher = storeA.get(PRIV_KEY);
        // 같은 키를 다시 암호화 저장
        ReflectionTestUtils.invokeMethod(first, "storeKeyPairInRedis", new KeyPair(first.getPublicKey(), first.getPrivateKey()));
        assertThat(storeA.get(PRIV_KEY)).isNotEqualTo(firstCipher);
    }

    @Test
    @DisplayName("A2a: 레거시 평문 키는 그대로 읽히고, 시크릿이 설정되면 암호화 형식으로 재저장된다")
    void legacyPlaintextIsMigrated() throws Exception {
        KeyPair legacy = newKeyPair();
        Map<String, String> store = new HashMap<>();
        store.put(PRIV_KEY, Base64.getEncoder().encodeToString(legacy.getPrivate().getEncoded()));
        store.put(PUB_KEY, Base64.getEncoder().encodeToString(legacy.getPublic().getEncoded()));

        JwtKeyProvider p = provider(fakeRedis(store), "kid-1", SECRET);
        p.init();

        assertThat(p.getPrivateKey()).isEqualTo(legacy.getPrivate());
        assertThat(store.get(PRIV_KEY)).startsWith("enc:v1:");

        JwtKeyProvider again = provider(fakeRedis(store), "kid-1", SECRET);
        again.init();
        assertThat(again.getPrivateKey()).isEqualTo(legacy.getPrivate());
    }

    @Test
    @DisplayName("A2a: 시크릿이 없으면 기존처럼 평문 저장/복원 (동작 불변)")
    void withoutSecretKeepsPlaintextBehaviour() {
        Map<String, String> store = new HashMap<>();
        JwtKeyProvider first = provider(fakeRedis(store), "kid-1", "");
        first.init();
        assertThat(store.get(PRIV_KEY)).doesNotStartWith("enc:v1:");

        JwtKeyProvider second = provider(fakeRedis(store), "kid-1", "");
        second.init();
        assertThat(second.getPrivateKey()).isEqualTo(first.getPrivateKey());
    }

    @Test
    @DisplayName("A2a: 암호화된 키가 있는데 시크릿이 틀리면 기동 실패하고 저장된 키를 덮어쓰지 않는다")
    void wrongSecretFailsAndDoesNotOverwrite() {
        Map<String, String> store = new HashMap<>();
        provider(fakeRedis(store), "kid-1", SECRET).init();
        String storedBefore = store.get(PRIV_KEY);

        JwtKeyProvider wrong = provider(fakeRedis(store), "kid-1", "some-other-secret");
        assertThatThrownBy(wrong::init).isInstanceOf(IllegalStateException.class);
        assertThat(store.get(PRIV_KEY)).isEqualTo(storedBefore);
    }

    @Test
    @DisplayName("A2a: 암호화된 키가 있는데 시크릿이 비어 있으면 기동 실패하고 덮어쓰지 않는다")
    void missingSecretFailsAndDoesNotOverwrite() {
        Map<String, String> store = new HashMap<>();
        provider(fakeRedis(store), "kid-1", SECRET).init();
        String storedBefore = store.get(PRIV_KEY);

        JwtKeyProvider none = provider(fakeRedis(store), "kid-1", "");
        assertThatThrownBy(none::init).isInstanceOf(IllegalStateException.class);
        assertThat(store.get(PRIV_KEY)).isEqualTo(storedBefore);
    }

    // ---------------- A2(b) ----------------

    @Test
    @DisplayName("A2b: 이전 키가 있으면 JWKS 에 현재 키가 먼저, 이전 키가 다음에 각자의 kid 로 게시된다")
    void jwksListsCurrentThenPrevious() throws Exception {
        KeyPair previous = newKeyPair();
        JwtKeyProvider p = provider(null, "kid-new", "");
        ReflectionTestUtils.setField(p, "previousKeyId", "kid-old");
        ReflectionTestUtils.setField(p, "previousPublicKeyPem", pem("PUBLIC KEY", previous.getPublic().getEncoded()));
        p.init();

        List<Map<String, Object>> keys = keys(p);
        assertThat(keys).hasSize(2);
        assertThat(keys.get(0).get("kid")).isEqualTo("kid-new");
        assertThat(keys.get(1).get("kid")).isEqualTo("kid-old");
        byte[] n = Base64.getUrlDecoder().decode((String) keys.get(1).get("n"));
        assertThat(new BigInteger(1, n)).isEqualTo(((RSAPublicKey) previous.getPublic()).getModulus());
    }

    @Test
    @DisplayName("A2b: 이전 키로 서명된 토큰도 검증되고, 새 토큰은 현재 키로 서명되며, 모르는 키로 서명된 토큰은 거부된다")
    void tokenSignedByPreviousKeyStillVerifies() throws Exception {
        KeyPair previous = newKeyPair();
        JwtKeyProvider p = provider(null, "kid-new", "");
        ReflectionTestUtils.setField(p, "previousKeyId", "kid-old");
        ReflectionTestUtils.setField(p, "previousPublicKeyPem", pem("PUBLIC KEY", previous.getPublic().getEncoded()));
        p.init();
        JwtTokenProvider tokens = new JwtTokenProvider(p);
        ReflectionTestUtils.setField(tokens, "issuer", "https://auth.doro.local");
        ReflectionTestUtils.setField(tokens, "accessTokenValiditySeconds", 900L);

        Claims claims = tokens.parseAndValidateToken(sign(previous, "kid-old"));
        assertThat(claims.getSubject()).isNotBlank();

        String fresh = tokens.createAccessToken(UUID.randomUUID(), "a@b.c", UUID.randomUUID(), 0);
        assertThat(Jwts.parser().verifyWith(p.getPublicKey()).build().parseSignedClaims(fresh).getHeader().getKeyId())
                .isEqualTo("kid-new");

        // kid 만 위조하고 다른 키로 서명한 토큰은 거부
        KeyPair attacker = newKeyPair();
        assertThatThrownBy(() -> tokens.parseAndValidateToken(sign(attacker, "kid-old")))
                .isInstanceOf(com.hunnit_beasts.auth.common.exception.AuthException.class);
    }

    @Test
    @DisplayName("A2b: 이전 키가 없으면 이전 kid 로 서명된 토큰은 거부된다 (기본 동작 불변)")
    void withoutPreviousKeyOldTokensAreRejected() throws Exception {
        KeyPair previous = newKeyPair();
        JwtKeyProvider p = provider(null, "kid-new", "");
        p.init();
        JwtTokenProvider tokens = new JwtTokenProvider(p);

        assertThatThrownBy(() -> tokens.parseAndValidateToken(sign(previous, "kid-old")))
                .isInstanceOf(com.hunnit_beasts.auth.common.exception.AuthException.class);
    }

    @Test
    @DisplayName("A2b: 현재 kid 와 이전 kid 가 같으면 기동 실패")
    void sameKidFailsStartup() throws Exception {
        KeyPair previous = newKeyPair();
        JwtKeyProvider p = provider(null, "same-kid", "");
        ReflectionTestUtils.setField(p, "previousKeyId", "same-kid");
        ReflectionTestUtils.setField(p, "previousPublicKeyPem", pem("PUBLIC KEY", previous.getPublic().getEncoded()));

        assertThatThrownBy(p::init).isInstanceOf(IllegalStateException.class).hasMessageContaining("must differ");
    }
}

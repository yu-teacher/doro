package com.hunnit_beasts.auth.domain.social.service;

import com.hunnit_beasts.auth.common.util.EmailNormalizer;
import com.hunnit_beasts.auth.core.crypto.CustomArgon2PasswordEncoder;
import com.hunnit_beasts.auth.domain.auth.dto.TokenResponse;
import com.hunnit_beasts.auth.domain.auth.service.AuthService;
import com.hunnit_beasts.auth.domain.credential.entity.Credential;
import com.hunnit_beasts.auth.domain.credential.repository.CredentialRepository;
import com.hunnit_beasts.auth.domain.session.service.SessionRevocationService;
import com.hunnit_beasts.auth.domain.social.entity.SocialIdentity;
import com.hunnit_beasts.auth.domain.social.provider.SocialLoginException;
import com.hunnit_beasts.auth.domain.social.provider.SocialProfile;
import com.hunnit_beasts.auth.domain.social.provider.SocialProvider;
import com.hunnit_beasts.auth.domain.social.repository.SocialIdentityRepository;
import com.hunnit_beasts.auth.domain.user.entity.User;
import com.hunnit_beasts.auth.domain.user.entity.UserRole;
import com.hunnit_beasts.auth.domain.user.entity.UserStatus;
import com.hunnit_beasts.auth.domain.user.repository.UserRepository;
import com.hunnit_beasts.auth.domain.user.service.UserRelationSyncService;
import com.hunnit_beasts.auth.common.log.LogMasking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 소셜 로그인(GitHub, Google ...). 공급자에서 이메일 소유가 확인된 사용자만 받는다.
 *
 * <ul>
 *   <li>이미 연결된 외부 계정이면 그 Doro 계정으로 로그인한다.</li>
 *   <li>아니면 같은 이메일의 Doro 계정에 자동 연결한다. 그 계정의 이메일은 가입 때 확인된 적이 없을 수 있으므로(남이 먼저
 *       그 이메일로 가입해 둔 경우), 이메일 소유가 처음 확인되는 순간 예전 비밀번호·2FA 를 버리고 모든 세션을 끝낸다.</li>
 *   <li>같은 이메일의 계정이 없으면 비밀번호 없는 새 계정을 만든다.</li>
 * </ul>
 *
 * <p>CSRF·코드 가로채기 방어: state 는 서버가 한 번만 쓰게 보관하고(브라우저 쿠키와도 맞춘다), PKCE(S256)를 쓴다.
 * 로그인 시작 상태는 단일 인스턴스 메모리에 둔다(2FA 임시 티켓과 같은 방식).
 */
@Slf4j
@Service
public class SocialLoginService {

    /** 로그인 시작 뒤 공급자 화면에서 돌아오기까지 허용하는 시간. */
    private static final long STATE_TTL_SECONDS = 600;
    private static final SecureRandom RANDOM = new SecureRandom();

    private record Pending(String provider, String codeVerifier, int slot, Instant expiresAt) {}

    /** 로그인 시작 결과: 공급자 화면 주소와 브라우저에 묶을 state. */
    public record Started(URI redirect, String state) {}

    /** 콜백 결과: 토큰을 발급했거나(tokens), 2FA 가 필요하다(ticket). slot 은 시작할 때 받은 쿠키 슬롯. */
    public record Outcome(TokenResponse tokens, String twoFactorTicket, int slot) {}

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, SocialProvider> providers = new java.util.LinkedHashMap<>();

    private final UserRepository userRepository;
    private final CredentialRepository credentialRepository;
    private final SocialIdentityRepository identityRepository;
    private final AuthService authService;
    private final SessionRevocationService sessionRevocationService;
    private final UserRelationSyncService userRelationSyncService;
    private final CustomArgon2PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;

    @Value("${doro.social.public-base-url:}")
    private String publicBaseUrl;

    @Value("${doro.social.max-pending-logins:10000}")
    private int maxPending;

    public SocialLoginService(List<SocialProvider> providerBeans,
                              UserRepository userRepository,
                              CredentialRepository credentialRepository,
                              SocialIdentityRepository identityRepository,
                              AuthService authService,
                              SessionRevocationService sessionRevocationService,
                              UserRelationSyncService userRelationSyncService,
                              CustomArgon2PasswordEncoder passwordEncoder,
                              TransactionTemplate transactionTemplate) {
        providerBeans.forEach(p -> providers.put(p.id(), p));
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.identityRepository = identityRepository;
        this.authService = authService;
        this.sessionRevocationService = sessionRevocationService;
        this.userRelationSyncService = userRelationSyncService;
        this.passwordEncoder = passwordEncoder;
        this.transactionTemplate = transactionTemplate;
    }

    /** 지금 로그인 화면에 보일 수 있는(설정이 끝난) 공급자. 공개 주소가 없으면 콜백을 받을 수 없으므로 비어 있다. */
    public List<SocialProvider> enabledProviders() {
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            return List.of();
        }
        return providers.values().stream().filter(SocialProvider::isConfigured).toList();
    }

    public Started start(String providerId, int slot) {
        SocialProvider provider = enabledProviders().stream()
                .filter(p -> p.id().equals(providerId)).findFirst()
                .orElseThrow(() -> new SocialLoginException(SocialLoginException.UNAVAILABLE, "사용할 수 없는 로그인 방식입니다."));

        purgeExpired(Instant.now());
        if (maxPending > 0 && pending.size() >= maxPending) {
            throw new SocialLoginException(SocialLoginException.TOO_MANY, "요청이 너무 많습니다.");
        }

        String state = randomToken();
        String verifier = randomToken();
        pending.put(state, new Pending(provider.id(), verifier, slot, Instant.now().plusSeconds(STATE_TTL_SECONDS)));
        return new Started(provider.authorizeUri(redirectUri(provider.id()), state, challengeOf(verifier)), state);
    }

    /** 공급자가 돌려준 코드로 로그인을 끝낸다. state 는 한 번만 쓸 수 있다. */
    public Outcome complete(String providerId, String code, String state, String ipAddress, String userAgent) {
        Pending entry = state == null ? null : pending.remove(state);
        if (entry == null || Instant.now().isAfter(entry.expiresAt()) || !entry.provider().equals(providerId)
                || code == null || code.isBlank()) {
            throw new SocialLoginException(SocialLoginException.INVALID_STATE, "로그인 요청이 만료되었거나 올바르지 않습니다.");
        }
        SocialProvider provider = enabledProviders().stream()
                .filter(p -> p.id().equals(providerId)).findFirst()
                .orElseThrow(() -> new SocialLoginException(SocialLoginException.UNAVAILABLE, "사용할 수 없는 로그인 방식입니다."));

        SocialProfile profile = provider.fetchProfile(code, redirectUri(providerId), entry.codeVerifier());
        if (profile.providerUserId() == null || profile.providerUserId().isBlank()) {
            throw new SocialLoginException(SocialLoginException.PROVIDER_ERROR, "사용자 식별자가 없습니다.");
        }
        if (!profile.emailVerified() || profile.email() == null || profile.email().isBlank()) {
            throw new SocialLoginException(SocialLoginException.EMAIL_NOT_VERIFIED, "공급자가 확인한 이메일이 없습니다.");
        }
        String email = EmailNormalizer.normalize(profile.email());

        User user = resolveUser(providerId, profile, email);

        if (!user.isActive() && !user.isPendingDeletion()) {
            throw new SocialLoginException(SocialLoginException.ACCOUNT_SUSPENDED, "정지되었거나 탈퇴한 계정입니다.");
        }

        // 2FA 를 켜 둔 계정은 소셜로 들어와도 OTP 를 한 번 더 확인한다(비밀번호 로그인과 같은 /2fa/login 으로 이어진다).
        Optional<Credential> credential = credentialRepository.findByUserId(user.getId());
        if (credential.isPresent() && credential.get().hasActiveTotp()) {
            return new Outcome(null, authService.openTwoFactorTicket(user.getId()), entry.slot());
        }
        TokenResponse tokens = authService.establishSession(user, null, ipAddress, userAgent);
        return new Outcome(tokens, null, entry.slot());
    }

    /** 쓰지 않게 된 로그인 시작 상태를 버린다(사용자가 공급자 화면에서 취소한 경우). */
    public void discard(String state) {
        if (state != null) {
            pending.remove(state);
        }
    }

    private User resolveUser(String providerId, SocialProfile profile, String email) {
        try {
            return resolveUserOnce(providerId, profile, email);
        } catch (DataIntegrityViolationException e) {
            // 같은 사람이 동시에 두 번 들어와 같은 이메일/외부 계정을 만들려 한 경쟁. 이제는 행이 있으니 한 번 더 찾는다.
            return resolveUserOnce(providerId, profile, email);
        }
    }

    private User resolveUserOnce(String providerId, SocialProfile profile, String email) {
        Optional<SocialIdentity> linked = identityRepository.findByProviderAndProviderUserId(providerId, profile.providerUserId());
        if (linked.isPresent()) {
            User user = userRepository.findById(linked.get().getUserId()).orElseThrow();
            transactionTemplate.executeWithoutResult(status -> identityRepository.findById(linked.get().getId())
                    .ifPresent(SocialIdentity::touchLogin));
            return user;
        }

        Optional<User> existing = userRepository.findByEmailIgnoreCase(email);
        // 해시(Argon2id, 느림)는 트랜잭션 밖에서 만든다. 필요 없는 경로(이미 확인된 계정에 추가 연결)에서는 만들지 않는다.
        String unusableHash = existing.map(u -> u.isEmailVerified() ? null : newUnusableHash()).orElseGet(this::newUnusableHash);

        User user = transactionTemplate.execute(status -> {
            User target;
            if (existing.isPresent()) {
                target = userRepository.findById(existing.get().getId()).orElseThrow();
                if (target.isActive() || target.isPendingDeletion()) {
                    adoptVerifiedEmail(target, unusableHash);
                }
            } else {
                target = userRepository.save(User.builder()
                        .email(email)
                        .name(displayName(profile, email))
                        .status(UserStatus.ACTIVE)
                        .emailVerified(true)
                        .build());
                Credential credential = Credential.builder()
                        .userId(target.getId())
                        .passwordHash(unusableHash)
                        .build();
                credential.discardPassword(unusableHash);
                credentialRepository.save(credential);
            }
            identityRepository.save(new SocialIdentity(target.getId(), providerId, profile.providerUserId(), email));
            return target;
        });
        if (existing.isEmpty()) {
            // 계정은 이미 만들어졌다. Guard 장애로 가입을 실패시키지 않는다(튜플은 기동 시 동기화가 복구).
            userRelationSyncService.syncUserTuplesBestEffort(user, UserRole.USER);
            log.info("User registered by social login: userId={}, provider={}, email={}",
                    user.getId(), providerId, LogMasking.maskEmail(email));
        } else {
            log.info("Social identity linked: userId={}, provider={}", user.getId(), providerId);
        }
        return user;
    }

    /**
     * 이 이메일의 소유가 공급자에서 확인됐다. 확인된 적 없는 계정이면 남이 먼저 만들어 둔 계정일 수 있으므로
     * 예전 비밀번호·2FA 를 버리고 모든 세션을 끝낸 뒤 확인된 계정으로 표시한다.
     */
    private void adoptVerifiedEmail(User user, String unusableHash) {
        if (user.isEmailVerified()) {
            return;
        }
        credentialRepository.findByUserId(user.getId()).ifPresent(credential -> credential.discardPassword(unusableHash));
        sessionRevocationService.revokeOtherSessions(user.getId(), null, "SOCIAL_EMAIL_VERIFIED");
        user.markEmailVerified();
        log.warn("Unverified password account adopted by a verified social email: userId={} (password, 2FA and sessions discarded)",
                user.getId());
    }

    private String newUnusableHash() {
        return passwordEncoder.encode(randomToken());
    }

    private static String displayName(SocialProfile profile, String email) {
        String name = profile.name() == null ? "" : profile.name().trim();
        if (name.isEmpty()) {
            name = email.substring(0, email.indexOf('@'));
        }
        return name.length() > 100 ? name.substring(0, 100) : name;
    }

    private String redirectUri(String providerId) {
        String base = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
        return base + "/api/v1/auth/social/" + providerId + "/callback";
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String challengeOf(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Scheduled(fixedDelayString = "${doro.social.cleanup-interval-ms:60000}",
            initialDelayString = "${doro.social.cleanup-initial-delay-ms:60000}")
    public void purgeExpired() {
        purgeExpired(Instant.now());
    }

    /** @return 제거한 개수 */
    public int purgeExpired(Instant now) {
        int before = pending.size();
        pending.values().removeIf(p -> now.isAfter(p.expiresAt()));
        return before - pending.size();
    }

    public int pendingCount() {
        return pending.size();
    }
}

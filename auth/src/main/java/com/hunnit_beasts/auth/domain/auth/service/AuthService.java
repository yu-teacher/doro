package com.hunnit_beasts.auth.domain.auth.service;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.common.log.LogMasking;
import com.hunnit_beasts.auth.common.util.EmailNormalizer;
import com.hunnit_beasts.auth.core.crypto.CustomArgon2PasswordEncoder;
import com.hunnit_beasts.auth.core.token.JwtTokenProvider;
import com.hunnit_beasts.auth.core.token.RefreshTokenService;
import com.hunnit_beasts.auth.core.totp.TotpService;
import com.hunnit_beasts.auth.domain.auth.dto.*;
import com.hunnit_beasts.auth.domain.credential.entity.Credential;
import com.hunnit_beasts.auth.domain.credential.repository.CredentialRepository;
import com.hunnit_beasts.auth.domain.credential.service.CredentialService;
import com.hunnit_beasts.auth.domain.oauth.service.OAuth2Service;
import com.hunnit_beasts.auth.domain.session.entity.UserSession;
import com.hunnit_beasts.auth.domain.session.service.SessionRevocationService;
import com.hunnit_beasts.auth.domain.session.service.SessionService;
import com.hunnit_beasts.auth.domain.user.entity.User;
import com.hunnit_beasts.auth.domain.user.entity.UserRole;
import com.hunnit_beasts.auth.domain.user.entity.UserStatus;
import com.hunnit_beasts.auth.domain.user.repository.UserRepository;
import com.hunnit_beasts.auth.domain.user.service.UserRelationSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final CredentialRepository credentialRepository;
    private final CredentialService credentialService;
    private final CustomArgon2PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final SessionService sessionService;
    private final TotpService totpService;
    private final SessionRevocationService sessionRevocationService;
    private final UserRelationSyncService userRelationSyncService;

    private record TwoFactorTicketSession(UUID userId, AtomicInteger failedAttempts, Instant expiresAt) {}

    private final Map<String, TwoFactorTicketSession> pendingTwoFactorTickets = new ConcurrentHashMap<>();

    @Value("${doro.iam.issuer:https://auth.doro.local}")
    private String issuer;

    @Value("${doro.iam.jwt.access-token-validity-seconds:900}")
    private long accessTokenValiditySeconds;

    /** 동시에 보관할 2FA 임시 티켓의 상한. 초과 시(만료분 정리 후에도) 신규 발급을 429 로 거부한다. */
    @Value("${doro.iam.two-factor.max-pending-tickets:10000}")
    private int maxPendingTwoFactorTickets;

    /** 만료된 2FA 임시 티켓을 주기적으로 정리한다. 로그인하지 않고 방치된 티켓이 메모리에 쌓이는 것을 막는다. */
    @Scheduled(fixedDelayString = "${doro.iam.two-factor.cleanup-interval-ms:60000}",
            initialDelayString = "${doro.iam.two-factor.cleanup-initial-delay-ms:60000}")
    public void purgeExpiredTwoFactorTickets() {
        purgeExpiredTwoFactorTickets(Instant.now());
    }

    /** @return 제거한 티켓 수 */
    public int purgeExpiredTwoFactorTickets(Instant now) {
        int before = pendingTwoFactorTickets.size();
        pendingTwoFactorTickets.values().removeIf(t -> now.isAfter(t.expiresAt()));
        int removed = before - pendingTwoFactorTickets.size();
        if (removed > 0) {
            log.debug("Purged {} expired 2FA ticket(s)", removed);
        }
        return removed;
    }

    public int pendingTwoFactorTicketCount() {
        return pendingTwoFactorTickets.size();
    }

    private void requireTicketCapacity() {
        if (maxPendingTwoFactorTickets <= 0 || pendingTwoFactorTickets.size() < maxPendingTwoFactorTickets) {
            return;
        }
        purgeExpiredTwoFactorTickets(Instant.now());
        if (pendingTwoFactorTickets.size() >= maxPendingTwoFactorTickets) {
            log.warn("Pending 2FA ticket capacity reached: limit={}", maxPendingTwoFactorTickets);
            throw new AuthException(ErrorCode.TOO_MANY_REQUESTS);
        }
    }

    @Transactional
    public UUID signup(SignUpRequest request) {
        // 이메일은 trim + 소문자로 정규화해 저장하고, 중복 검사는 (과거 혼합 대소문자 데이터 포함) 대소문자를 무시한다.
        String email = EmailNormalizer.normalize(request.email());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new AuthException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        User user = User.builder()
                .email(email)
                .name(request.name())
                .status(UserStatus.ACTIVE)
                .build();
        User savedUser = userRepository.save(user);

        String encodedPassword = passwordEncoder.encode(request.password());
        Credential credential = Credential.builder()
                .userId(savedUser.getId())
                .passwordHash(encodedPassword)
                .build();
        credentialRepository.save(credential);

        userRelationSyncService.syncUserTuples(savedUser, savedUser.getRole() != null ? savedUser.getRole() : UserRole.USER);

        log.info("User registered successfully and synced to Zanzibar ReBAC: userId={}, email={}",
                savedUser.getId(), LogMasking.maskEmail(savedUser.getEmail()));
        return savedUser.getId();
    }

    @Transactional(readOnly = true)
    public AccountLookupResponse lookupAccount(String email) {
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND, "Doro 계정을 찾을 수 없습니다."));

        // 탈퇴 유예 중인 계정은 다시 로그인해 복구할 수 있어야 하므로 조회를 막지 않는다.
        if (!user.isActive() && !user.isPendingDeletion()) {
            throw new AuthException(ErrorCode.ACCOUNT_SUSPENDED, "이용이 정지된 계정입니다.");
        }

        return new AccountLookupResponse(user.getEmail(), user.getName(), user.getProfileImageUrl());
    }

    @Transactional
    public LoginResponse login(LoginRequest request, String ipAddress, String userAgent) {
        User user = userRepository.findByEmailIgnoreCase(request.email())
                .orElseThrow(() -> new AuthException(ErrorCode.INVALID_CREDENTIALS));

        // 탈퇴 유예 중인 계정은 비밀번호를 확인한 뒤 세션을 발급할 때 복구한다. (비밀번호 검증 전에는 상태를 드러내지 않는다)
        if (!user.isActive() && !user.isPendingDeletion()) {
            throw new AuthException(ErrorCode.ACCOUNT_SUSPENDED);
        }

        Credential credential = credentialRepository.findByUserId(user.getId())
                .orElseThrow(() -> new AuthException(ErrorCode.INVALID_CREDENTIALS));

        if (credential.isLocked()) {
            throw new AuthException(ErrorCode.ACCOUNT_LOCKED);
        }

        if (!passwordEncoder.matches(request.password(), credential.getPasswordHash())) {
            credentialService.recordFailedAttempt(user.getId());
            log.warn("Invalid password attempt for user {}. Failed count: {}",
                    LogMasking.maskEmail(user.getEmail()), credential.getFailedAttempts() + 1);
            throw new AuthException(ErrorCode.INVALID_CREDENTIALS);
        }

        // 2FA 등록 여부 확인 (2FA 계정은 OTP 까지 통과해야 실패 카운트를 초기화한다)
        if (credential.getTotpSecret() != null && !credential.getTotpSecret().isBlank()) {
            requireTicketCapacity();
            String tempTicket = UUID.randomUUID().toString();
            pendingTwoFactorTickets.put(tempTicket, new TwoFactorTicketSession(
                    user.getId(),
                    new AtomicInteger(0),
                    Instant.now().plusSeconds(300) // 5분 유효
            ));
            return LoginResponse.requiresTwoFactor(tempTicket);
        }

        credentialService.resetFailedAttempts(user.getId());
        TokenResponse tokenResponse = issueSessionAndTokens(user, request.deviceInfo(), ipAddress, userAgent);
        return LoginResponse.directSuccess(tokenResponse);
    }

    @Transactional
    public TokenResponse loginWithTotp(TotpLoginRequest request, String ipAddress, String userAgent) {
        TwoFactorTicketSession session = pendingTwoFactorTickets.get(request.tempTicket());
        if (session == null || Instant.now().isAfter(session.expiresAt())) {
            if (session != null) {
                pendingTwoFactorTickets.remove(request.tempTicket());
            }
            throw new AuthException(ErrorCode.INVALID_TOKEN, "2FA 임시 티켓이 만료되었거나 유효하지 않습니다. 다시 로그인해 주세요.");
        }

        User user = userRepository.findById(session.userId())
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));

        // 티켓 발급 이후에 정지·영구 탈퇴된 계정은 OTP 가 맞아도 세션을 받지 못한다.
        if (!user.isActive() && !user.isPendingDeletion()) {
            pendingTwoFactorTickets.remove(request.tempTicket());
            throw new AuthException(ErrorCode.ACCOUNT_SUSPENDED);
        }

        Credential credential = credentialRepository.findByUserId(session.userId())
                .orElseThrow(() -> new AuthException(ErrorCode.INVALID_CREDENTIALS));

        if (credential.isLocked()) {
            throw new AuthException(ErrorCode.ACCOUNT_LOCKED);
        }

        if (!totpService.verifyAndConsume(user.getId(), credential.getTotpSecret(), request.code())) {
            // OTP 실패도 계정 잠금 카운트에 합산한다 (티켓을 새로 받아 무한 시도하는 것을 막는다)
            credentialService.recordFailedAttempt(user.getId());
            int attempts = session.failedAttempts().incrementAndGet();
            if (attempts >= 5) {
                pendingTwoFactorTickets.remove(request.tempTicket());
                throw new AuthException(ErrorCode.INVALID_2FA_CODE, "2차 인증 5회 실패로 임시 티켓이 만료되었습니다. 처음부터 다시 로그인해 주세요.");
            }
            throw new AuthException(ErrorCode.INVALID_2FA_CODE, "2차 인증(OTP) 코드가 올바르지 않습니다. (남은 시도: " + (5 - attempts) + "회)");
        }

        // 인증 성공 시에만 티켓 즉시 파기
        pendingTwoFactorTickets.remove(request.tempTicket());
        credentialService.resetFailedAttempts(user.getId());
        return issueSessionAndTokens(user, request.deviceInfo(), ipAddress, userAgent);
    }

    /** 2FA 등록 시작: 시크릿을 "대기" 상태로만 저장한다. verifyTotp 로 코드를 확인해야 활성화된다. */
    @Transactional
    public TotpSetupResponse setupTotp(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));

        Credential credential = credentialRepository.findByUserId(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.INVALID_CREDENTIALS));

        if (credential.hasActiveTotp()) {
            throw new AuthException(ErrorCode.INVALID_INPUT, "이미 2단계 인증이 활성화되어 있습니다. 해제한 뒤 다시 설정해 주세요.");
        }

        String secret = totpService.generateSecret();
        credential.beginTotpEnrollment(secret);

        String qrUri = totpService.generateQrUri(user.getEmail(), secret, "Doro");
        return new TotpSetupResponse(secret, qrUri);
    }

    /**
     * 등록 대기 중이면 코드를 확인해 2FA 를 활성화하고, 이미 활성화된 계정이면 코드만 검증한다.
     * 실패는 계정 잠금 카운트에 합산한다.
     */
    @Transactional
    public void verifyTotp(UUID userId, String code) {
        Credential credential = credentialRepository.findByUserId(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.INVALID_CREDENTIALS));

        if (credential.isLocked()) {
            throw new AuthException(ErrorCode.ACCOUNT_LOCKED);
        }

        if (credential.hasPendingTotp()) {
            if (!totpService.verifyAndConsume(userId, credential.getPendingTotpSecret(), code)) {
                credentialService.recordFailedAttempt(userId);
                throw new AuthException(ErrorCode.INVALID_2FA_CODE);
            }
            credential.confirmTotpEnrollment();
            log.info("User 2FA (TOTP) enabled after code confirmation: userId={}", userId);
            return;
        }

        if (!credential.hasActiveTotp() || !totpService.verifyAndConsume(userId, credential.getTotpSecret(), code)) {
            credentialService.recordFailedAttempt(userId);
            throw new AuthException(ErrorCode.INVALID_2FA_CODE);
        }
    }

    /** 2FA 해제는 현재 유효한 OTP 코드로 재인증해야 한다. (탈취된 세션만으로 보호를 끄지 못하게 한다) */
    @Transactional
    public void disableTotp(UUID userId, String code) {
        Credential credential = credentialRepository.findByUserId(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.INVALID_CREDENTIALS));

        if (credential.isLocked()) {
            throw new AuthException(ErrorCode.ACCOUNT_LOCKED);
        }

        if (!credential.hasActiveTotp()) {
            credential.clearPendingTotp();
            return;
        }

        if (!totpService.verifyAndConsume(userId, credential.getTotpSecret(), code)) {
            credentialService.recordFailedAttempt(userId);
            throw new AuthException(ErrorCode.INVALID_2FA_CODE);
        }
        credential.updateTotpSecret(null);
        credential.clearPendingTotp();
        log.info("User 2FA (TOTP) disabled after code re-authentication: userId={}", userId);
    }

    @Transactional
    public TokenResponse refresh(RefreshTokenRequest request) {
        // OAuth 클라이언트용 세션의 리프레시 토큰으로 일반 로그인 토큰(사용자의 실제 role, cid 없음)을 발급받으면 OAuth 토큰 격리가
        // 무너진다. 회전하기 전에 확인해서, 거부된 시도가 정상 클라이언트의 토큰을 소모하지도 않게 한다.
        refreshTokenService.findSessionByRawToken(request.refreshToken())
                .filter(OAuth2Service::isOAuthSession)
                .ifPresent(oauthSession -> {
                    log.warn("Refresh through the first-party endpoint rejected: the token belongs to an OAuth client session");
                    throw new AuthException(ErrorCode.INVALID_TOKEN);
                });

        RefreshTokenService.RotatedTokenResult result = refreshTokenService.rotateRefreshToken(request.refreshToken());
        UserSession session = result.session();

        User user = userRepository.findById(session.getUserId())
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));

        if (!user.isActive()) {
            session.deactivate();
            throw new AuthException(ErrorCode.ACCOUNT_SUSPENDED);
        }

        String newAccessToken = jwtTokenProvider.createAccessToken(
                user.getId(),
                user.getEmail(),
                session.getId(),
                session.getUserIndex(),
                user.getRole() != null ? user.getRole().name() : "USER"
        );

        return TokenResponse.of(
                newAccessToken,
                result.newRefreshToken(),
                accessTokenValiditySeconds,
                session.getId(),
                session.getUserIndex()
        );
    }

    @Transactional
    public void logout(UUID userId, UUID sessionId) {
        sessionRevocationService.revokeOwnSession(userId, sessionId, "LOGOUT");
        log.info("Session terminated and killswitch sent: sessionId={}", sessionId);
    }

    private TokenResponse issueSessionAndTokens(User user, String deviceInfo, String ipAddress, String userAgent) {
        if (user.isPendingDeletion()) {
            // 비밀번호(와 2FA)를 통과했으므로 본인이 맞다. 유예 기간 안에 로그인했으니 탈퇴를 취소한다.
            // 영구 처리와 경쟁할 수 있어 조건부 UPDATE 로 복구하고, 그사이 처리됐다면 세션을 발급하지 않는다.
            int restored = userRepository.restoreIfPendingDeletion(
                    user.getId(), UserStatus.ACTIVE, UserStatus.PENDING_DELETION, Instant.now());
            if (restored == 0) {
                throw new AuthException(ErrorCode.ACCOUNT_SUSPENDED);
            }
            // 영구 처리가 Guard 튜플을 먼저 지웠을 수 있으므로 되살린다. (멱등)
            userRelationSyncService.syncUserTuples(user, user.getRole() != null ? user.getRole() : UserRole.USER);
            log.info("Account deletion cancelled by login during the grace period: userId={}", user.getId());
        }

        UserSession session = sessionService.createSession(
                user.getId(),
                deviceInfo,
                ipAddress,
                userAgent
        );

        String accessToken = jwtTokenProvider.createAccessToken(
                user.getId(),
                user.getEmail(),
                session.getId(),
                session.getUserIndex(),
                user.getRole() != null ? user.getRole().name() : "USER"
        );
        String refreshToken = refreshTokenService.createRefreshToken(session.getId());

        log.info("User session established: userId={}, sessionId={}, userIndex={}",
                user.getId(), session.getId(), session.getUserIndex());

        return TokenResponse.of(
                accessToken,
                refreshToken,
                accessTokenValiditySeconds,
                session.getId(),
                session.getUserIndex()
        );
    }
}

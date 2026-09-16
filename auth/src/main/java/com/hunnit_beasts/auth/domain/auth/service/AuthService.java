package com.hunnit_beasts.auth.domain.auth.service;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.core.crypto.CustomArgon2PasswordEncoder;
import com.hunnit_beasts.auth.core.redis.KillSwitchPublisher;
import com.hunnit_beasts.auth.core.token.JwtTokenProvider;
import com.hunnit_beasts.auth.core.token.RefreshTokenService;
import com.hunnit_beasts.auth.core.totp.TotpService;
import com.hunnit_beasts.auth.domain.auth.dto.*;
import com.hunnit_beasts.auth.domain.credential.entity.Credential;
import com.hunnit_beasts.auth.domain.credential.repository.CredentialRepository;
import com.hunnit_beasts.auth.domain.credential.service.CredentialService;
import com.hunnit_beasts.auth.domain.session.entity.UserSession;
import com.hunnit_beasts.auth.domain.session.service.SessionService;
import com.hunnit_beasts.auth.domain.user.entity.User;
import com.hunnit_beasts.auth.domain.user.entity.UserRole;
import com.hunnit_beasts.auth.domain.user.entity.UserStatus;
import com.hunnit_beasts.auth.domain.user.repository.UserRepository;
import com.hunnit_beasts.auth.domain.user.service.UserRelationSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
    private final KillSwitchPublisher killSwitchPublisher;
    private final UserRelationSyncService userRelationSyncService;

    private record TwoFactorTicketSession(UUID userId, AtomicInteger failedAttempts, Instant expiresAt) {}

    private final Map<String, TwoFactorTicketSession> pendingTwoFactorTickets = new ConcurrentHashMap<>();

    @Value("${doro.iam.issuer:https://auth.doro.local}")
    private String issuer;

    @Value("${doro.iam.jwt.access-token-validity-seconds:900}")
    private long accessTokenValiditySeconds;

    @Transactional
    public UUID signup(SignUpRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new AuthException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        User user = User.builder()
                .email(request.email())
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

        log.info("User registered successfully and synced to Zanzibar ReBAC: userId={}, email={}", savedUser.getId(), savedUser.getEmail());
        return savedUser.getId();
    }

    @Transactional(readOnly = true)
    public AccountLookupResponse lookupAccount(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND, "Doro 계정을 찾을 수 없습니다."));

        if (!user.isActive()) {
            throw new AuthException(ErrorCode.ACCOUNT_SUSPENDED, "이용이 정지된 계정입니다.");
        }

        return new AccountLookupResponse(user.getEmail(), user.getName(), user.getProfileImageUrl());
    }

    @Transactional
    public LoginResponse login(LoginRequest request, String ipAddress, String userAgent) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new AuthException(ErrorCode.INVALID_CREDENTIALS));

        if (!user.isActive()) {
            throw new AuthException(ErrorCode.ACCOUNT_SUSPENDED);
        }

        Credential credential = credentialRepository.findByUserId(user.getId())
                .orElseThrow(() -> new AuthException(ErrorCode.INVALID_CREDENTIALS));

        if (credential.isLocked()) {
            throw new AuthException(ErrorCode.ACCOUNT_LOCKED);
        }

        if (!passwordEncoder.matches(request.password(), credential.getPasswordHash())) {
            credentialService.recordFailedAttempt(user.getId());
            log.warn("Invalid password attempt for user {}. Failed count: {}", user.getEmail(), credential.getFailedAttempts() + 1);
            throw new AuthException(ErrorCode.INVALID_CREDENTIALS);
        }

        credentialService.resetFailedAttempts(user.getId());

        // 2FA 등록 여부 확인
        if (credential.getTotpSecret() != null && !credential.getTotpSecret().isBlank()) {
            String tempTicket = UUID.randomUUID().toString();
            pendingTwoFactorTickets.put(tempTicket, new TwoFactorTicketSession(
                    user.getId(),
                    new AtomicInteger(0),
                    Instant.now().plusSeconds(300) // 5분 유효
            ));
            return LoginResponse.requiresTwoFactor(tempTicket);
        }

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

        Credential credential = credentialRepository.findByUserId(session.userId())
                .orElseThrow(() -> new AuthException(ErrorCode.INVALID_CREDENTIALS));

        if (!totpService.verifyCode(credential.getTotpSecret(), request.code())) {
            int attempts = session.failedAttempts().incrementAndGet();
            if (attempts >= 5) {
                pendingTwoFactorTickets.remove(request.tempTicket());
                throw new AuthException(ErrorCode.INVALID_2FA_CODE, "2차 인증 5회 실패로 임시 티켓이 만료되었습니다. 처음부터 다시 로그인해 주세요.");
            }
            throw new AuthException(ErrorCode.INVALID_2FA_CODE, "2차 인증(OTP) 코드가 올바르지 않습니다. (남은 시도: " + (5 - attempts) + "회)");
        }

        // 인증 성공 시에만 티켓 즉시 파기
        pendingTwoFactorTickets.remove(request.tempTicket());
        return issueSessionAndTokens(user, request.deviceInfo(), ipAddress, userAgent);
    }

    @Transactional
    public TotpSetupResponse setupTotp(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));

        Credential credential = credentialRepository.findByUserId(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.INVALID_CREDENTIALS));

        String secret = totpService.generateSecret();
        credential.updateTotpSecret(secret);

        String qrUri = totpService.generateQrUri(user.getEmail(), secret, "Doro");
        return new TotpSetupResponse(secret, qrUri);
    }

    @Transactional
    public void verifyTotp(UUID userId, String code) {
        Credential credential = credentialRepository.findByUserId(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.INVALID_CREDENTIALS));

        if (credential.getTotpSecret() == null || !totpService.verifyCode(credential.getTotpSecret(), code)) {
            throw new AuthException(ErrorCode.INVALID_2FA_CODE);
        }
    }

    @Transactional
    public void disableTotp(UUID userId) {
        Credential credential = credentialRepository.findByUserId(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.INVALID_CREDENTIALS));
        credential.updateTotpSecret(null);
        log.info("User 2FA (TOTP) disabled successfully: userId={}", userId);
    }

    @Transactional
    public TokenResponse refresh(RefreshTokenRequest request) {
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
    public void logout(UUID sessionId) {
        UserSession session = sessionService.getActiveSession(sessionId);
        sessionService.deactivateSession(sessionId);
        refreshTokenService.revokeAllForSession(sessionId);
        killSwitchPublisher.publishSessionRevoked(session.getUserId(), sessionId, "LOGOUT");
        log.info("Session terminated and killswitch sent: sessionId={}", sessionId);
    }

    private TokenResponse issueSessionAndTokens(User user, String deviceInfo, String ipAddress, String userAgent) {
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

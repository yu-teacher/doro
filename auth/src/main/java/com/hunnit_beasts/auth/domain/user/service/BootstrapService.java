package com.hunnit_beasts.auth.domain.user.service;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.common.exception.ErrorCode;
import com.hunnit_beasts.auth.domain.session.service.SessionRevocationService;
import com.hunnit_beasts.auth.domain.user.entity.User;
import com.hunnit_beasts.auth.domain.user.entity.UserRole;
import com.hunnit_beasts.auth.domain.user.entity.UserStatus;
import com.hunnit_beasts.auth.domain.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * 첫 관리자 부트스트랩. 운영자가 환경변수(DORO_IAM_BOOTSTRAP_TOKEN)에 일회용 토큰을 두면, 그 토큰을 아는 로그인 사용자가
 * 본인을 SUPER_ADMIN 으로 승격할 수 있다. 다음을 모두 만족할 때만 동작한다.
 * <ul>
 *   <li>토큰이 설정돼 있고 충분히 길다(그렇지 않으면 기능이 없는 것처럼 404).</li>
 *   <li>제시한 토큰이 맞다(상수 시간 비교).</li>
 *   <li>영구 탈퇴하지 않은 SUPER_ADMIN 이 한 명도 없다(있으면 409). 한 번 성공하면 토큰이 남아 있어도 다시는 쓸 수 없다.</li>
 *   <li>호출자가 정상(ACTIVE) 계정이다.</li>
 * </ul>
 * 동시 호출은 JVM 락으로 직렬화한다(IAM 은 단일 인스턴스). 성공하면 Guard 튜플을 동기화하고 모든 세션을 끝내 다시 로그인하게 한다.
 */
@Slf4j
@Service
public class BootstrapService {

    /** 이보다 짧은 토큰은 추측이 쉬우므로 기능을 켜지 않는다. */
    static final int MIN_TOKEN_LENGTH = 32;

    private final UserRepository userRepository;
    private final UserRelationSyncService userRelationSyncService;
    private final SessionRevocationService sessionRevocationService;
    private final TransactionTemplate transactionTemplate;
    private final String configuredToken;
    private final Object lock = new Object();

    public BootstrapService(UserRepository userRepository,
                            UserRelationSyncService userRelationSyncService,
                            SessionRevocationService sessionRevocationService,
                            TransactionTemplate transactionTemplate,
                            @Value("${doro.iam.bootstrap.token:}") String configuredToken) {
        this.userRepository = userRepository;
        this.userRelationSyncService = userRelationSyncService;
        this.sessionRevocationService = sessionRevocationService;
        this.transactionTemplate = transactionTemplate;
        this.configuredToken = configuredToken == null ? "" : configuredToken.trim();
    }

    boolean enabled() {
        return configuredToken.length() >= MIN_TOKEN_LENGTH;
    }

    /** 기동 시 상태를 로그로 남겨, 쓰고 난 토큰이 환경에 남아 있는 것을 눈에 띄게 한다. */
    @EventListener(ApplicationReadyEvent.class)
    public void logStatusOnStartup() {
        if (!configuredToken.isEmpty() && !enabled()) {
            log.warn("DORO_IAM_BOOTSTRAP_TOKEN 이 {}자 미만이라 첫 관리자 부트스트랩을 켜지 않았다.", MIN_TOKEN_LENGTH);
            return;
        }
        if (!enabled()) {
            return;
        }
        long superAdmins = userRepository.countByRoleExcludingStatus(UserRole.SUPER_ADMIN, UserStatus.DELETED);
        if (superAdmins > 0) {
            log.warn("부트스트랩 토큰이 아직 설정돼 있다. 최고 관리자가 이미 있어 쓸 수는 없지만 .env 에서 지우세요.");
        } else {
            log.warn("첫 관리자 부트스트랩이 켜져 있다(최고 관리자 없음). 승격이 끝나면 DORO_IAM_BOOTSTRAP_TOKEN 을 지우세요.");
        }
    }

    public void claimSuperAdmin(UUID userId, String presentedToken) {
        if (!enabled()) {
            throw new AuthException(ErrorCode.BOOTSTRAP_UNAVAILABLE);
        }
        if (!tokenMatches(presentedToken)) {
            log.warn("SECURITY: first-admin bootstrap rejected: wrong token, userId={}", userId);
            throw new AuthException(ErrorCode.ACCESS_DENIED);
        }
        synchronized (lock) {
            transactionTemplate.executeWithoutResult(status -> promote(userId));
        }
    }

    private void promote(UUID userId) {
        if (userRepository.countByRoleExcludingStatus(UserRole.SUPER_ADMIN, UserStatus.DELETED) > 0) {
            log.warn("First-admin bootstrap refused: a super admin already exists, userId={}", userId);
            throw new AuthException(ErrorCode.BOOTSTRAP_ALREADY_DONE);
        }
        User user = userRepository.findById(userId).orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new AuthException(ErrorCode.ACCOUNT_STATE_CONFLICT, "정상 상태의 계정만 최고 관리자가 될 수 있습니다.");
        }
        user.changeRole(UserRole.SUPER_ADMIN);
        // Guard 동기화가 실패하면 예외가 트랜잭션을 되돌려 DB 역할도 바뀌지 않는다.
        userRelationSyncService.syncUserTuplesOrThrow(user, UserRole.SUPER_ADMIN);
        // 기존 토큰의 역할 클레임이 낡았으므로 모든 세션을 끝내 다시 로그인하게 한다.
        sessionRevocationService.revokeOtherSessions(userId, null, "ROLE_CHANGED");
        log.warn("First super admin bootstrapped: userId={}. 토큰을 .env 에서 지우세요.", userId);
    }

    private boolean tokenMatches(String presented) {
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                configuredToken.getBytes(StandardCharsets.UTF_8));
    }
}

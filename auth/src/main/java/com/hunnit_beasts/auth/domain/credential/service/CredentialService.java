package com.hunnit_beasts.auth.domain.credential.service;

import com.hunnit_beasts.auth.common.web.ClientIpResolver;
import com.hunnit_beasts.auth.domain.credential.entity.Credential;
import com.hunnit_beasts.auth.domain.credential.repository.CredentialRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.UUID;

/**
 * 인증 실패에 따른 잠금 정책의 진입점. 실제 계수는 {@link LoginAttemptTracker}(계정+IP 쌍, 계정 전체)가 하고,
 * 이 클래스는 현재 요청의 클라이언트 IP 를 붙이고 계정 전체 잠금을 DB 에 기록한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CredentialService {

    private final CredentialRepository credentialRepository;
    private final LoginAttemptTracker attempts;
    private final ClientIpResolver clientIpResolver;

    /** 계정 전체 잠금(DB) 이거나, 이 요청의 IP 와 계정 쌍이 잠겨 있으면 true. */
    public boolean isLocked(Credential credential) {
        return credential.isLocked() || attempts.isPairLocked(credential.getUserId(), currentIp());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailedAttempt(UUID userId) {
        LoginAttemptTracker.Result result = attempts.recordFailure(userId, currentIp());
        if (result == LoginAttemptTracker.Result.ACCOUNT_LOCKED) {
            credentialRepository.lockAccountUntil(userId, Instant.now().plus(attempts.accountLock()), attempts.accountMax());
        }
        log.warn("Failed attempt recorded for userId={}, result={}", userId, result);
    }

    /** 성공: 이 IP 의 쌍 실패와 DB 의 잠금 상태를 초기화한다. 계정 전체 합산은 그대로 둔다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resetFailedAttempts(UUID userId) {
        attempts.recordSuccess(userId, currentIp());
        credentialRepository.findByUserId(userId).ifPresent(credential -> {
            if (credential.getFailedAttempts() > 0 || credential.getLockedUntil() != null) {
                credential.resetFailedAttempts();
                credentialRepository.saveAndFlush(credential);
            }
        });
    }

    /**
     * 본인 확인이 성공했을 때 이 IP 의 쌍 실패만 지운다(DB 는 건드리지 않는다). 엔티티를 직접 초기화하는 흐름
     * (2FA 등록, 비밀번호 변경)이 트랜잭션 안에서 호출한다.
     */
    public void clearPairFailures(UUID userId) {
        attempts.recordSuccess(userId, currentIp());
    }

    /** 관리자 해제용: 이 계정의 모든 쌍 잠금과 계정 합산을 지운다(DB 잠금은 호출 측이 푼다). */
    public void clearAllLockState(UUID userId) {
        attempts.clearAccount(userId);
    }

    /** 요청 스레드에서만 의미가 있다. 요청 밖(스케줄러·단위 시험)이면 "UNKNOWN". */
    private String currentIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            // IPv6 는 /64 로 묶는다(주소만 바꿔 쌍 잠금을 피하지 못하게)
            return com.hunnit_beasts.auth.common.web.IpPrefix.rateLimitKey(clientIpResolver.resolve(attrs.getRequest()));
        }
        return "UNKNOWN";
    }
}

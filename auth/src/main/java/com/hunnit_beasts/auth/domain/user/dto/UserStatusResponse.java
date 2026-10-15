package com.hunnit_beasts.auth.domain.user.dto;

import java.util.UUID;

/**
 * 서브 서비스가 "이 사용자가 지금 쓸 수 있는 계정인가"를 확인하는 내부 응답. 개인정보 없이 상태만 담는다.
 * status: ACTIVE(정상) / SUSPENDED(이용 정지) / PENDING_DELETION(탈퇴 유예 중) / DELETED(영구 탈퇴) 등 {@code UserStatus} 이름.
 */
public record UserStatusResponse(UUID userId, String status) {}

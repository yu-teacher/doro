package com.hunnit_beasts.auth.domain.session.dto;

import com.hunnit_beasts.auth.domain.user.entity.UserStatus;

import java.time.Instant;

/** 세션 생존 판정용 읽기 전용 투영: 세션의 활성 여부/만료 시각과 소유 사용자의 상태. */
public record SessionLiveness(boolean active, Instant expiresAt, UserStatus userStatus) {
}

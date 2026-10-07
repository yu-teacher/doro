package com.hunnit_beasts.auth.domain.user.dto;

import java.time.Instant;
import java.util.UUID;

/** 영구 탈퇴한 사용자의 ID 와 처리 시각. 이메일·이름 같은 개인정보는 담지 않는다. */
public record DeletedUserRef(UUID userId, Instant deletedAt) {
}

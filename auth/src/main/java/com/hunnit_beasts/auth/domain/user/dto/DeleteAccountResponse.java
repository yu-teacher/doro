package com.hunnit_beasts.auth.domain.user.dto;

import java.time.Instant;

/** @param scheduledPurgeAt 이 시각이 지나면 개인정보가 영구적으로 익명화된다. 그 전에 로그인하면 탈퇴가 취소된다. */
public record DeleteAccountResponse(Instant scheduledPurgeAt) {
}

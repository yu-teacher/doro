package com.hunnit_beasts.auth.domain.user.entity;

public enum UserStatus {
    ACTIVE,
    LOCKED,
    SUSPENDED,
    /** 탈퇴 요청 후 유예 중. 로그인하면 복구되고, 유예가 끝나면 DELETED 가 된다. */
    PENDING_DELETION,
    /** 개인정보가 익명화된 영구 탈퇴 상태. */
    DELETED
}

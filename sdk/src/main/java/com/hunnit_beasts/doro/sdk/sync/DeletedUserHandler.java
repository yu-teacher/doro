package com.hunnit_beasts.doro.sdk.sync;

import java.time.Instant;
import java.util.UUID;

/**
 * Doro 에서 영구 탈퇴한 사용자의 데이터를 서비스가 정리한다(익명화, 사진 삭제, 권한 튜플 정리, BFF 세션 삭제 등).
 * 같은 사용자가 다시 와도 결과가 같아야 한다(멱등). 예외를 던지면 그 실행은 실패로 끝나고 다음 주기에 같은 사용자부터 이어서 처리한다.
 */
@FunctionalInterface
public interface DeletedUserHandler {

    /** @return 이번 호출에서 실제로 무언가를 정리했으면 true(이미 처리된 사용자면 false) */
    boolean onDeleted(UUID userId, Instant deletedAt);
}

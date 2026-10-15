package com.hunnit_beasts.doro.sdk.sync;

import java.time.Instant;
import java.util.Optional;

/** 주기 동기화가 어디까지 진행됐는지(증분 조회 기준 시각)를 이름별로 보관한다. 서비스가 자기 DB 로 구현한다. */
public interface SyncCursorStore {

    Optional<Instant> get(String name);

    /** 기준 시각을 앞으로만 옮긴다. 저장된 값보다 이르면 무시한다. */
    void advance(String name, Instant cursor);
}

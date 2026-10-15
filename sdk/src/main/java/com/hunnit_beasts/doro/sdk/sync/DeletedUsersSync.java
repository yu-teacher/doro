package com.hunnit_beasts.doro.sdk.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Doro 가 알려 주는 '영구 탈퇴한 사용자 ID' 를 주기적으로 가져와 서비스의 {@link DeletedUserHandler} 로 정리한다.
 * IAM 이 잠시 죽어 있어도 다음 주기에 이어서 처리하고, 같은 ID 를 다시 받아도 결과가 같다(멱등).
 */
public class DeletedUsersSync {

    private static final Logger log = LoggerFactory.getLogger(DeletedUsersSync.class);

    private final DeletedUsersClient iam;
    private final DeletedUserHandler handler;
    private final SyncCursorStore cursors;
    private final DeletedUsersProperties props;
    private final Clock clock;

    public DeletedUsersSync(DeletedUsersClient iam, DeletedUserHandler handler, SyncCursorStore cursors,
                            DeletedUsersProperties props, Clock clock) {
        this.iam = iam;
        this.handler = handler;
        this.cursors = cursors;
        this.props = props;
        this.clock = clock;
    }

    /** 스케줄러가 부른다. 실패는 로그만 남기고 다음 주기에 이어서 처리한다. */
    public void scheduledRun() {
        if (!props.isEnabled()) {
            return;
        }
        try {
            int processed = sync();
            if (processed > 0) {
                log.info("Deleted-account sync finished: handled={}", processed);
            }
        } catch (Exception e) {
            log.warn("Deleted-account sync failed, will retry on the next run: {}", e.toString());
        }
    }

    /** @return 이번 실행에서 새로 정리한 사용자 수. 실패하면 예외를 던지고, 이미 처리한 페이지의 진행 상황은 유지된다. */
    public int sync() {
        Instant stored = cursors.get(props.getName()).orElse(Instant.EPOCH);
        Instant since = stored;
        int handled = 0;
        while (true) {
            DeletedUsersClient.DeletedUsersPage page = iam.fetchDeletedUsers(since, props.getPageSize());
            for (DeletedUsersClient.DeletedUser item : page.items()) {
                if (handler.onDeleted(item.userId(), clock.instant())) {
                    handled++;
                }
            }
            Instant next = page.nextSince();
            cursors.advance(props.getName(), next.minus(props.getOverlap()));
            if (page.items().size() < props.getPageSize()) {
                return handled;
            }
            if (!next.isAfter(since)) {
                // 한 페이지가 전부 같은 시각이면 더 나아갈 수 없다. 무한 반복하지 않고 멈춘다.
                log.warn("Deleted-account sync cannot advance past {} (page full of identical timestamps)", since);
                return handled;
            }
            since = next;
        }
    }
}

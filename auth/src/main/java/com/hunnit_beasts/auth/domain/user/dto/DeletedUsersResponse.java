package com.hunnit_beasts.auth.domain.user.dto;

import java.time.Instant;
import java.util.List;

/** @param nextSince 다음 조회에 넘길 since. 겹쳐 받아도 되도록 마지막 항목의 시각(없으면 요청한 since)을 그대로 준다. */
public record DeletedUsersResponse(List<DeletedUserRef> items, Instant nextSince) {
}

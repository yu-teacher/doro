package com.hunnit_beasts.guard.domain.tuple.service;

import com.hunnit_beasts.guard.core.engine.CheckEngine;
import com.hunnit_beasts.guard.domain.tuple.dto.TupleDto;
import com.hunnit_beasts.guard.domain.tuple.repository.RelationTupleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TupleService {

    private final RelationTupleRepository tupleRepository;
    private final CheckEngine checkEngine;
    private final TupleSchemaValidator schemaValidator;

    @Transactional
    public int writeTuples(List<TupleDto> dtos) {
        // 같은 배치 안의 중복 튜플은 하나로 합친다.
        Map<String, TupleDto> unique = new LinkedHashMap<>();
        for (TupleDto dto : dtos) {
            unique.putIfAbsent(keyOf(dto), dto);
        }

        // ENFORCE 에서는 배치 전체를 쓰기 전에 거부한다 (WARN 은 로그만).
        schemaValidator.validate(unique.values());

        int written = 0;
        for (TupleDto dto : unique.values()) {
            String subRel = normalize(dto.subjectRelation());
            boolean exists = (subRel == null)
                    ? tupleRepository.existsDirectTuple(dto.namespace(), dto.objectId(), dto.relation(), dto.subjectNamespace(), dto.subjectId())
                    : tupleRepository.existsUsersetTuple(dto.namespace(), dto.objectId(), dto.relation(), dto.subjectNamespace(), dto.subjectId(), subRel);
            if (exists) {
                continue;
            }
            // 동시 쓰기와 경합해도 DB 유니크 인덱스가 중복을 막고, 충돌 시 예외 없이 0 을 반환한다.
            written += tupleRepository.insertIfAbsent(UUID.randomUUID(), dto.namespace(), dto.objectId(), dto.relation(),
                    dto.subjectNamespace(), dto.subjectId(), subRel);
        }

        if (written > 0) {
            invalidateCacheAfterCommit();
            log.info("Successfully written {} relation tuples", written);
        }
        return written;
    }

    @Transactional
    public int deleteTuples(List<TupleDto> dtos) {
        int deletedCount = 0;
        for (TupleDto dto : dtos) {
            deletedCount += tupleRepository.deleteTuple(
                    dto.namespace(),
                    dto.objectId(),
                    dto.relation(),
                    dto.subjectNamespace(),
                    dto.subjectId(),
                    normalize(dto.subjectRelation())
            );
        }

        if (deletedCount > 0) {
            invalidateCacheAfterCommit();
            log.info("Successfully deleted {} relation tuples", deletedCount);
        }
        return deletedCount;
    }

    /** 커밋 전에 캐시를 비우면 동시 조회가 커밋 이전 데이터를 다시 캐시에 채울 수 있으므로 커밋 이후에 비운다. */
    private void invalidateCacheAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    checkEngine.invalidateCache();
                }
            });
        } else {
            checkEngine.invalidateCache();
        }
    }

    private static String normalize(String subjectRelation) {
        return (subjectRelation != null && !subjectRelation.isBlank()) ? subjectRelation : null;
    }

    private static String keyOf(TupleDto dto) {
        return String.join("\u0000", dto.namespace(), dto.objectId(), dto.relation(),
                dto.subjectNamespace(), dto.subjectId(), String.valueOf(normalize(dto.subjectRelation())));
    }
}

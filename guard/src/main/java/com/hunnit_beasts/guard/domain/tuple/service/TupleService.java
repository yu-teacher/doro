package com.hunnit_beasts.guard.domain.tuple.service;

import com.hunnit_beasts.guard.core.engine.CheckEngine;
import com.hunnit_beasts.guard.domain.tuple.dto.TupleDto;
import com.hunnit_beasts.guard.domain.tuple.entity.RelationTuple;
import com.hunnit_beasts.guard.domain.tuple.repository.RelationTupleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TupleService {

    private final RelationTupleRepository tupleRepository;
    private final CheckEngine checkEngine;

    @Transactional
    public int writeTuples(List<TupleDto> dtos) {
        List<RelationTuple> entities = new ArrayList<>();

        for (TupleDto dto : dtos) {
            String subRel = (dto.subjectRelation() != null && !dto.subjectRelation().isBlank()) ? dto.subjectRelation() : null;
            boolean exists = (subRel == null)
                    ? tupleRepository.existsDirectTuple(dto.namespace(), dto.objectId(), dto.relation(), dto.subjectNamespace(), dto.subjectId())
                    : tupleRepository.existsUsersetTuple(dto.namespace(), dto.objectId(), dto.relation(), dto.subjectNamespace(), dto.subjectId(), subRel);

            if (!exists) {
                entities.add(RelationTuple.builder()
                        .namespace(dto.namespace())
                        .objectId(dto.objectId())
                        .relation(dto.relation())
                        .subjectNamespace(dto.subjectNamespace())
                        .subjectId(dto.subjectId())
                        .subjectRelation(subRel)
                        .build());
            }
        }

        if (!entities.isEmpty()) {
            tupleRepository.saveAll(entities);
            checkEngine.invalidateCache();
            log.info("Successfully written {} relation tuples", entities.size());
        }

        return entities.size();
    }

    @Transactional
    public int deleteTuples(List<TupleDto> dtos) {
        int deletedCount = 0;
        for (TupleDto dto : dtos) {
            String subRel = (dto.subjectRelation() != null && !dto.subjectRelation().isBlank()) ? dto.subjectRelation() : null;
            deletedCount += tupleRepository.deleteTuple(
                    dto.namespace(),
                    dto.objectId(),
                    dto.relation(),
                    dto.subjectNamespace(),
                    dto.subjectId(),
                    subRel
            );
        }

        if (deletedCount > 0) {
            checkEngine.invalidateCache();
            log.info("Successfully deleted {} relation tuples", deletedCount);
        }

        return deletedCount;
    }
}

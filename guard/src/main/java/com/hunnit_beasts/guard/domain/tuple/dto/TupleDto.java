package com.hunnit_beasts.guard.domain.tuple.dto;

import jakarta.validation.constraints.NotBlank;

public record TupleDto(
        @NotBlank String namespace,
        @NotBlank String objectId,
        @NotBlank String relation,
        @NotBlank String subjectNamespace,
        @NotBlank String subjectId,
        String subjectRelation
) {
    public static TupleDto of(String namespace, String objectId, String relation,
                              String subjectNamespace, String subjectId) {
        return new TupleDto(namespace, objectId, relation, subjectNamespace, subjectId, null);
    }

    public static TupleDto of(String namespace, String objectId, String relation,
                              String subjectNamespace, String subjectId, String subjectRelation) {
        return new TupleDto(namespace, objectId, relation, subjectNamespace, subjectId, subjectRelation);
    }
}

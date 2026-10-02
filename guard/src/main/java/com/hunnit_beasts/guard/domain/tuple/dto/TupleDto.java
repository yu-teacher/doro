package com.hunnit_beasts.guard.domain.tuple.dto;

import com.hunnit_beasts.guard.common.validation.FieldLimits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TupleDto(
        @NotBlank @Size(max = FieldLimits.NAME_MAX) String namespace,
        @NotBlank @Size(max = FieldLimits.ID_MAX) String objectId,
        @NotBlank @Size(max = FieldLimits.NAME_MAX) String relation,
        @NotBlank @Size(max = FieldLimits.NAME_MAX) String subjectNamespace,
        @NotBlank @Size(max = FieldLimits.ID_MAX) String subjectId,
        @Size(max = FieldLimits.NAME_MAX) String subjectRelation
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

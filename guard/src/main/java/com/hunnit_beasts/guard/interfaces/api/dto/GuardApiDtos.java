package com.hunnit_beasts.guard.interfaces.api.dto;

import com.hunnit_beasts.guard.common.validation.FieldLimits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class GuardApiDtos {

    public record CheckApiRequest(
            @NotBlank @Size(max = FieldLimits.NAME_MAX) String namespace,
            @NotBlank @Size(max = FieldLimits.ID_MAX) String objectId,
            @NotBlank @Size(max = FieldLimits.NAME_MAX) String relation,
            @NotBlank @Size(max = FieldLimits.NAME_MAX) String subjectNamespace,
            @NotBlank @Size(max = FieldLimits.ID_MAX) String subjectId,
            @Size(max = FieldLimits.NAME_MAX) String subjectRelation
    ) {}

    public record CheckApiResponse(
            boolean allowed,
            int depth,
            String reason
    ) {}

    public record SchemaRegisterRequest(
            @NotBlank String dsl
    ) {}

    public record SchemaResponse(
            int version,
            String dsl,
            boolean active
    ) {}
}

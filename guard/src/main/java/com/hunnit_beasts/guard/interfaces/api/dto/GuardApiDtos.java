package com.hunnit_beasts.guard.interfaces.api.dto;

import jakarta.validation.constraints.NotBlank;

public class GuardApiDtos {

    public record CheckApiRequest(
            @NotBlank String namespace,
            @NotBlank String objectId,
            @NotBlank String relation,
            @NotBlank String subjectNamespace,
            @NotBlank String subjectId,
            String subjectRelation
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

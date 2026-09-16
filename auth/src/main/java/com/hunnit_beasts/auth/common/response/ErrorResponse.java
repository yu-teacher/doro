package com.hunnit_beasts.auth.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    @Builder.Default
    private Instant timestamp = Instant.now();
    private int status;
    private String error;
    private String code;
    private String message;
    private String path;
    private List<FieldErrorDetail> details;

    @Getter
    @Builder
    public static class FieldErrorDetail {
        private String field;
        private Object rejectedValue;
        private String reason;
    }
}

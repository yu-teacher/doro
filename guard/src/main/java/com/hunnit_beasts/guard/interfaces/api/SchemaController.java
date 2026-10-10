package com.hunnit_beasts.guard.interfaces.api;

import com.hunnit_beasts.guard.common.response.ApiResponse;
import com.hunnit_beasts.guard.config.CallerNamespaceGuard;
import com.hunnit_beasts.guard.config.ServiceAuthProperties;
import com.hunnit_beasts.guard.core.dsl.service.SchemaService;
import com.hunnit_beasts.guard.domain.schema.entity.SchemaDefinition;
import com.hunnit_beasts.guard.interfaces.api.dto.GuardApiDtos.SchemaRegisterRequest;
import com.hunnit_beasts.guard.interfaces.api.dto.GuardApiDtos.SchemaResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/guard/schema")
@RequiredArgsConstructor
public class SchemaController {

    private final SchemaService schemaService;
    private final CallerNamespaceGuard namespaceGuard;

    @GetMapping
    public ApiResponse<String> getActiveSchema() {
        return ApiResponse.success(schemaService.getActiveDslText());
    }

    @PostMapping
    public ApiResponse<SchemaResponse> registerSchema(
            @RequestAttribute(name = ServiceAuthProperties.CALLER_ATTRIBUTE, required = false) String caller,
            @Valid @RequestBody SchemaRegisterRequest request) {
        namespaceGuard.requireSchemaChange(caller, request.dsl());
        SchemaDefinition definition = schemaService.registerSchema(request.dsl());
        return ApiResponse.success(new SchemaResponse(
                definition.getVersion(),
                definition.getDslText(),
                definition.isActive()
        ));
    }
}

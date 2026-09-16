package com.hunnit_beasts.guard.interfaces.api;

import com.hunnit_beasts.guard.common.response.ApiResponse;
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

    @GetMapping
    public ApiResponse<String> getActiveSchema() {
        return ApiResponse.success(schemaService.getActiveDslText());
    }

    @PostMapping
    public ApiResponse<SchemaResponse> registerSchema(@Valid @RequestBody SchemaRegisterRequest request) {
        SchemaDefinition definition = schemaService.registerSchema(request.dsl());
        return ApiResponse.success(new SchemaResponse(
                definition.getVersion(),
                definition.getDslText(),
                definition.isActive()
        ));
    }
}

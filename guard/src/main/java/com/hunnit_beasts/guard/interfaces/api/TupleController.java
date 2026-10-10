package com.hunnit_beasts.guard.interfaces.api;

import com.hunnit_beasts.guard.common.response.ApiResponse;
import com.hunnit_beasts.guard.config.CallerNamespaceGuard;
import com.hunnit_beasts.guard.config.ServiceAuthProperties;
import com.hunnit_beasts.guard.domain.tuple.dto.TupleDto;
import com.hunnit_beasts.guard.domain.tuple.service.TupleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/guard/tuples")
@RequiredArgsConstructor
public class TupleController {

    private final TupleService tupleService;
    private final CallerNamespaceGuard namespaceGuard;

    @PostMapping
    public ApiResponse<Map<String, Integer>> writeTuples(
            @RequestAttribute(name = ServiceAuthProperties.CALLER_ATTRIBUTE, required = false) String caller,
            @RequestBody List<@Valid TupleDto> tuples) {
        namespaceGuard.requireTupleAccess(caller, "tuple write", tuples);
        int written = tupleService.writeTuples(tuples);
        return ApiResponse.success(Map.of("writtenCount", written));
    }

    @DeleteMapping
    public ApiResponse<Map<String, Integer>> deleteTuples(
            @RequestAttribute(name = ServiceAuthProperties.CALLER_ATTRIBUTE, required = false) String caller,
            @RequestBody List<@Valid TupleDto> tuples) {
        namespaceGuard.requireTupleAccess(caller, "tuple delete", tuples);
        int deleted = tupleService.deleteTuples(tuples);
        return ApiResponse.success(Map.of("deletedCount", deleted));
    }
}

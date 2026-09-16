package com.hunnit_beasts.guard.interfaces.api;

import com.hunnit_beasts.guard.common.response.ApiResponse;
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

    @PostMapping
    public ApiResponse<Map<String, Integer>> writeTuples(@Valid @RequestBody List<TupleDto> tuples) {
        int written = tupleService.writeTuples(tuples);
        return ApiResponse.success(Map.of("writtenCount", written));
    }

    @DeleteMapping
    public ApiResponse<Map<String, Integer>> deleteTuples(@Valid @RequestBody List<TupleDto> tuples) {
        int deleted = tupleService.deleteTuples(tuples);
        return ApiResponse.success(Map.of("deletedCount", deleted));
    }
}

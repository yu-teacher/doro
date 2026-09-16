package com.hunnit_beasts.guard.interfaces.api;

import com.hunnit_beasts.guard.common.response.ApiResponse;
import com.hunnit_beasts.guard.core.engine.CheckEngine;
import com.hunnit_beasts.guard.interfaces.api.dto.GuardApiDtos.CheckApiRequest;
import com.hunnit_beasts.guard.interfaces.api.dto.GuardApiDtos.CheckApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/guard/check")
@RequiredArgsConstructor
public class CheckController {

    private final CheckEngine checkEngine;

    @PostMapping
    public ApiResponse<CheckApiResponse> check(@Valid @RequestBody CheckApiRequest request) {
        CheckEngine.CheckResult result = checkEngine.check(
                request.namespace(),
                request.objectId(),
                request.relation(),
                request.subjectNamespace(),
                request.subjectId(),
                request.subjectRelation()
        );

        return ApiResponse.success(new CheckApiResponse(
                result.allowed(),
                result.maxDepthReached(),
                result.reason()
        ));
    }
}

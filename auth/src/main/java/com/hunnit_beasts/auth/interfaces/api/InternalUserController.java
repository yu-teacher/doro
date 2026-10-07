package com.hunnit_beasts.auth.interfaces.api;

import com.hunnit_beasts.auth.common.response.ApiResponse;
import com.hunnit_beasts.auth.domain.user.dto.DeletedUserRef;
import com.hunnit_beasts.auth.domain.user.dto.DeletedUsersResponse;
import com.hunnit_beasts.auth.domain.user.entity.UserStatus;
import com.hunnit_beasts.auth.domain.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 서브 서비스(블로그 등) 전용 내부 API. 게이트웨이가 라우팅하지 않는 /internal 경로이며 Docker 내부 네트워크에서만
 * 닿는다. 응답은 영구 탈퇴한 사용자의 ID 와 시각뿐이라 개인정보가 없다.
 */
@RestController
@RequestMapping("/internal/v1")
@RequiredArgsConstructor
public class InternalUserController {

    static final int DEFAULT_LIMIT = 200;
    static final int MAX_LIMIT = 500;

    private final UserRepository userRepository;

    @GetMapping("/deleted-users")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<DeletedUsersResponse>> deletedUsers(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        // 게이트웨이를 거친 요청에는 X-Forwarded-For 가 붙는다. 외부에서 들어온 요청은 이 경로가 없는 것처럼 응답한다.
        if (request.getHeader("X-Forwarded-For") != null || request.getHeader("X-Real-IP") != null) {
            return ResponseEntity.notFound().build();
        }
        Instant from = since != null ? since : Instant.EPOCH;
        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        List<DeletedUserRef> items = userRepository.findDeletedSince(UserStatus.DELETED, from, PageRequest.ofSize(size));
        Instant next = items.isEmpty() ? from : items.get(items.size() - 1).deletedAt();
        return ResponseEntity.ok(ApiResponse.success(new DeletedUsersResponse(items, next)));
    }
}

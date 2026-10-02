package com.hunnit_beasts.auth.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UrlPathHelper;

/**
 * 컨트롤러 매핑이 보는 것과 같은 경로를 돌려준다: 퍼센트 디코딩, 세미콜론 매개변수 제거, 중복 슬래시와 {@code .}/{@code ..} 정리.
 *
 * <p>{@code getRequestURI()} 는 디코딩 전 원본이라 {@code /api/v1/auth/log%69n} 처럼 쓰면 문자열 비교는 빗나가고
 * 컨트롤러 매핑은 {@code /api/v1/auth/login} 으로 해석한다. 경로로 보안 판단(속도 제한 등)을 하는 필터는 이 값을 써야 한다.
 */
public final class RequestPaths {

    private RequestPaths() {
    }

    public static String canonical(HttpServletRequest request) {
        return StringUtils.cleanPath(UrlPathHelper.defaultInstance.getPathWithinApplication(request));
    }
}

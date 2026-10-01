package com.hunnit_beasts.auth.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 실제 클라이언트 IP 를 판단한다. 신뢰하는 프록시(기본: 루프백과 도커 브리지 네트워크의 게이트웨이)가 설정한 X-Real-IP 만 믿고,
 * 그 외 연결(LAN 의 다른 기기, 인터넷 클라이언트)이 보낸 X-Real-IP / X-Forwarded-For 는 위조될 수 있으므로 무시한다.
 *
 * <p>신뢰 대상은 {@code doro.iam.trusted-proxies}(CIDR, 쉼표 구분)로 지정한다. 사설망 전체(192.168/16, 10/8)는 일부러
 * 기본값에 넣지 않는다. LAN 기기가 헤더만 바꿔서 IP 단위 요청 제한을 피할 수 있기 때문이다.
 */
@Component
public class ClientIpResolver {

    private static final Pattern IP_LITERAL = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");

    private final List<IpAddressMatcher> trustedProxies;

    public ClientIpResolver(
            @Value("${doro.iam.trusted-proxies:127.0.0.0/8,::1/128,172.16.0.0/12}") List<String> trustedProxyCidrs) {
        this.trustedProxies = trustedProxyCidrs.stream()
                .map(String::trim)
                .filter(cidr -> !cidr.isEmpty())
                .map(IpAddressMatcher::new)
                .toList();
    }

    public String resolve(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        String forwarded = request.getHeader("X-Real-IP");
        if (forwarded != null && IP_LITERAL.matcher(forwarded.trim()).matches() && isTrustedProxy(remote)) {
            return forwarded.trim();
        }
        return remote != null ? remote : "UNKNOWN";
    }

    boolean isTrustedProxy(String remoteAddress) {
        if (remoteAddress == null || !IP_LITERAL.matcher(remoteAddress).matches()) {
            return false;
        }
        try {
            return trustedProxies.stream().anyMatch(matcher -> matcher.matches(remoteAddress));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}

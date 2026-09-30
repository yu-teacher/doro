package com.hunnit_beasts.auth.common.web;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * 실제 클라이언트 IP 를 판단한다. 게이트웨이/프록시(사설망·루프백에서 온 연결)가 설정한 X-Real-IP 만 신뢰하고,
 * 그 외 연결에서는 클라이언트가 보낸 X-Real-IP 를 무시해 위조를 막는다.
 */
public final class ClientIpResolver {

    private static final Pattern IP_LITERAL = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");

    private ClientIpResolver() {
    }

    public static String resolve(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        String forwarded = request.getHeader("X-Real-IP");
        if (forwarded != null && IP_LITERAL.matcher(forwarded.trim()).matches() && isTrustedProxy(remote)) {
            return forwarded.trim();
        }
        return remote;
    }

    private static boolean isTrustedProxy(String remoteAddress) {
        if (remoteAddress == null || !IP_LITERAL.matcher(remoteAddress).matches()) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(remoteAddress);
            return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}

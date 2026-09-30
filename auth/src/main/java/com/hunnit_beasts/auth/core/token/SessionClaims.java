package com.hunnit_beasts.auth.core.token;

import io.jsonwebtoken.Claims;
import org.springframework.security.core.Authentication;

import java.util.UUID;

/** 인증 필터가 Authentication.details 에 보관한 JWT 클레임에서 현재 세션 ID(sid)를 꺼낸다. */
public final class SessionClaims {

    private SessionClaims() {
    }

    public static UUID currentSessionId(Authentication authentication) {
        if (authentication != null && authentication.getDetails() instanceof Claims claims) {
            String sid = claims.get("sid", String.class);
            if (sid != null) {
                try {
                    return UUID.fromString(sid);
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }
        return null;
    }
}

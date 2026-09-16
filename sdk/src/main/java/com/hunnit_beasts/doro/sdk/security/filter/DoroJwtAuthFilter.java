package com.hunnit_beasts.doro.sdk.security.filter;

import com.hunnit_beasts.doro.sdk.domain.DoroUser;
import com.hunnit_beasts.doro.sdk.domain.DoroUserContext;
import com.hunnit_beasts.doro.sdk.security.jwks.JwksKeyProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.PublicKey;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class DoroJwtAuthFilter extends OncePerRequestFilter {

    private final JwksKeyProvider jwksKeyProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        org.slf4j.MDC.put("traceId", traceId);
        response.setHeader("X-Trace-Id", traceId);

        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            try {
                DoroUser user = validateAndExtractUser(token);
                if (user != null) {
                    DoroUserContext.setCurrentUser(user);
                    if (user.userId() != null) {
                        org.slf4j.MDC.put("userId", user.userId().toString());
                    }
                    log.debug("Authenticated Doro user: userId={}, sid={}", user.userId(), user.sessionId());
                }
            } catch (Exception e) {
                log.warn("Failed to authenticate Doro JWT: {}", e.getMessage());
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            DoroUserContext.clear();
            org.slf4j.MDC.clear();
        }
    }

    private DoroUser validateAndExtractUser(String token) {
        // 1. JWT Header에서 kid 추출
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            return null;
        }

        Jws<Claims> claimsJws = Jwts.parser()
                .keyLocator(header -> {
                    String kid = (String) header.get("kid");
                    if (kid != null) {
                        return jwksKeyProvider.getPublicKey(kid);
                    }
                    return null;
                })
                .build()
                .parseSignedClaims(token);

        Claims claims = claimsJws.getPayload();
        String sub = claims.getSubject();
        String email = claims.get("email", String.class);
        String sid = claims.get("sid", String.class);
        Integer uidx = claims.get("uidx", Integer.class);

        UUID userId = (sub != null) ? UUID.fromString(sub) : null;
        UUID sessionId = (sid != null) ? UUID.fromString(sid) : null;
        int userIndex = (uidx != null) ? uidx : 0;
        String role = claims.get("role", String.class);

        return new DoroUser(userId, email, sessionId, userIndex, role != null ? role : "USER");
    }
}

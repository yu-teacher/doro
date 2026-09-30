package com.hunnit_beasts.guard.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Guard 호출자(auth, 서브 서비스)를 식별하는 공유 서비스 토큰 설정.
 * OFF: 검사하지 않음(기본) / WARN: 실패를 로그로만 남기고 통과 / ENFORCE: 실패 시 거부.
 * 호출자 모두가 토큰을 보내도록 배포한 뒤 OFF -> WARN -> ENFORCE 순서로 올린다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "doro.guard.security")
public class ServiceAuthProperties {

    public enum Mode { OFF, WARN, ENFORCE }

    public static final String HEADER_NAME = "X-Doro-Service-Token";

    private Mode mode = Mode.OFF;
    private String serviceToken = "";

    public boolean isActive() {
        return mode != Mode.OFF;
    }

    public boolean matches(String presented) {
        if (serviceToken == null || serviceToken.isEmpty() || presented == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                serviceToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                presented.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}

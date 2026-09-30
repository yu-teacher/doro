package com.hunnit_beasts.doro.sdk.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "doro")
public class DoroProperties {

    private IamProperties iam = new IamProperties();
    private GuardProperties guard = new GuardProperties();

    @Getter
    @Setter
    public static class IamProperties {
        private String jwksUri = "http://localhost:8080/.well-known/jwks.json";
        private String issuer = "https://auth.doro.local";
        /** iss 클레임 검증 모드: OFF(기본) | WARN(불일치 시 경고만) | ENFORCE(불일치 시 인증 실패) */
        private IssuerValidation issuerValidation = IssuerValidation.OFF;
        /** Authorization 헤더가 없을 때 액세스 토큰을 읽을 쿠키 이름. 비어 있으면 쿠키를 읽지 않는다. */
        private String cookieName = "";
        /** JWT 검증 시 허용하는 시계 오차(초) */
        private long clockSkewSeconds = 5;
    }

    public enum IssuerValidation { OFF, WARN, ENFORCE }

    @Getter
    @Setter
    public static class GuardProperties {
        private String grpcHost = "localhost";
        private int grpcPort = 9090;
        private boolean enabled = true;
    }
}

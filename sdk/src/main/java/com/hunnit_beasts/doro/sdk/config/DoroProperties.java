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
        /**
         * 필수 audience(aud 클레임, 문자열 또는 배열). 비어 있으면(기본) aud 를 검사하지 않는다.
         * 값이 있으면 항상 ENFORCE 로 동작하여 aud 에 해당 값이 없는 토큰은 인증 실패(익명 계속) 처리된다.
         */
        private String audience = "";
        /** 기동 시 JWKS 를 백그라운드로 미리 조회한다(best-effort). 실패해도 기동을 막지 않는다. */
        private boolean jwksPrefetch = true;
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
        /** Guard 서비스 토큰(X-Doro-Service-Token). 비어 있으면 헤더를 보내지 않는다. */
        private String serviceToken = "";
    }
}

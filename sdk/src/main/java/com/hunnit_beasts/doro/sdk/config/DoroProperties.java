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
        /**
         * 세션 폐기 확인 모드: OFF(기본, 추가 네트워크 호출 없음) | WARN(폐기된 세션이면 경고만 남기고 통과)
         * | ENFORCE(폐기된 세션이면 익명 처리). JWT 서명/만료 검증이 끝난 뒤 IAM 의
         * {@code GET /api/v1/sessions/current} 에 같은 토큰으로 질의한다(로그아웃/세션 종료를 서브 서비스에 즉시 반영).
         */
        private RevocationCheck revocationCheck = RevocationCheck.OFF;
        /**
         * 세션 확인 URL. 비어 있으면(기본) {@code jwks-uri} 의 scheme://host[:port] + {@code /api/v1/sessions/current} 로 유도한다.
         * jwks-uri 가 비어 있거나 해석할 수 없으면 확인을 비활성화하고 WARN 을 한 번 남긴다.
         */
        private String revocationUrl = "";
        /** "유효함" 응답을 sid 별로 재사용하는 시간(초). 폐기 응답은 토큰 exp 까지 캐시된다. */
        private long revocationCacheSeconds = 30;
        /** IAM 세션 확인 호출의 연결/읽기 타임아웃(밀리초) */
        private int revocationTimeoutMillis = 2000;
        /**
         * IAM 에 닿지 못하거나(연결 실패/타임아웃/5xx) 판정을 못 받았을 때 요청을 통과시킬지 여부(기본 true).
         * false 이고 모드가 ENFORCE 이면 익명으로 처리한다.
         */
        private boolean revocationFailOpen = true;
    }

    public enum RevocationCheck { OFF, WARN, ENFORCE }

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

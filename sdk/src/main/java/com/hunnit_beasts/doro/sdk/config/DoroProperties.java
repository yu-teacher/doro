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
    }

    @Getter
    @Setter
    public static class GuardProperties {
        private String grpcHost = "localhost";
        private int grpcPort = 9090;
        private boolean enabled = true;
    }
}

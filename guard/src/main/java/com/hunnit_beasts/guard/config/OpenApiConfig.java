package com.hunnit_beasts.guard.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI doroGuardOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Doro Guard (Zanzibar ReBAC) API Documentation")
                        .description("구글 Zanzibar 모델 기반 관계형 인가 엔진(ReBAC), 스키마 DSL 및 튜플 REST API")
                        .version("v1.0.0")
                        .contact(new Contact().name("Hunnit Beasts Security Team").email("security@hunnit-beasts.com")));
    }
}

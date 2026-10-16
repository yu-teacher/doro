package com.hunnit_beasts.doro.sdk.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DoroWeakSettingsTest {

    @Test
    @DisplayName("기본값은 issuer 검증 OFF / 폐기 확인 OFF / 평문 JWKS 를 모두 약한 설정으로 보고한다")
    void defaultsReportAllWeakSettings() {
        DoroProperties.IamProperties iam = new DoroProperties.IamProperties();

        List<String> weak = DoroAutoConfiguration.weakSettings(iam, iam.getRevocationCheck());

        assertThat(weak).hasSize(3);
        assertThat(weak.get(0)).contains("issuer-validation=OFF");
        assertThat(weak.get(1)).contains("revocation-check=OFF");
        assertThat(weak.get(2)).contains("plain HTTP");
    }

    @Test
    @DisplayName("폐기 확인 ENFORCE + fail-open 이면 fail-open 만 따로 보고한다")
    void failOpenReportedWhenEnforced() {
        DoroProperties.IamProperties iam = new DoroProperties.IamProperties();
        iam.setIssuerValidation(DoroProperties.IssuerValidation.ENFORCE);
        iam.setJwksUri("https://auth.example/.well-known/jwks.json");

        List<String> weak = DoroAutoConfiguration.weakSettings(iam, DoroProperties.RevocationCheck.ENFORCE);

        assertThat(weak).singleElement().asString().contains("revocation-fail-open=true");
    }

    @Test
    @DisplayName("모두 강하게 설정하면 보고할 것이 없다")
    void strictConfigurationReportsNothing() {
        DoroProperties.IamProperties iam = new DoroProperties.IamProperties();
        iam.setIssuerValidation(DoroProperties.IssuerValidation.ENFORCE);
        iam.setRevocationFailOpen(false);
        iam.setJwksUri("https://auth.example/.well-known/jwks.json");

        assertThat(DoroAutoConfiguration.weakSettings(iam, DoroProperties.RevocationCheck.ENFORCE)).isEmpty();
    }
}

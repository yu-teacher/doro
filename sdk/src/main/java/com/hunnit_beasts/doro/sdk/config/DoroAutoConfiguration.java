package com.hunnit_beasts.doro.sdk.config;

import com.hunnit_beasts.doro.sdk.aop.DoroGuardAspect;
import com.hunnit_beasts.doro.sdk.client.DoroGuardClient;
import com.hunnit_beasts.doro.sdk.security.filter.DoroJwtAuthFilter;
import com.hunnit_beasts.doro.sdk.security.jwks.JwksKeyProvider;
import com.hunnit_beasts.doro.sdk.security.revocation.SessionRevocationChecker;
import com.hunnit_beasts.doro.sdk.web.CurrentDoroUserArgumentResolver;
import com.hunnit_beasts.doro.sdk.web.DoroExceptionHandlerAdvice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.Ordered;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Slf4j
@AutoConfiguration
@EnableAspectJAutoProxy
@EnableConfigurationProperties(DoroProperties.class)
public class DoroAutoConfiguration implements WebMvcConfigurer {

    @Bean
    @ConditionalOnMissingBean
    public JwksKeyProvider jwksKeyProvider(DoroProperties properties) {
        JwksKeyProvider provider = new JwksKeyProvider(properties.getIam().getJwksUri());
        if (properties.getIam().isJwksPrefetch()) {
            provider.prefetchAsync();
        }
        return provider;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication
    public FilterRegistrationBean<DoroJwtAuthFilter> doroJwtAuthFilterRegistration(JwksKeyProvider jwksKeyProvider,
                                                                                    DoroProperties properties) {
        DoroProperties.IamProperties iam = properties.getIam();
        DoroProperties.RevocationCheck revocationMode = iam.getRevocationCheck();
        SessionRevocationChecker revocationChecker = null;
        if (revocationMode != DoroProperties.RevocationCheck.OFF) {
            String revocationUrl = SessionRevocationChecker.resolveUrl(iam.getRevocationUrl(), iam.getJwksUri());
            if (revocationUrl == null) {
                log.warn("Doro session revocation check disabled: revocation-url is empty and jwks-uri cannot be parsed");
                revocationMode = DoroProperties.RevocationCheck.OFF;
            } else {
                revocationChecker = new SessionRevocationChecker(
                        revocationUrl, iam.getRevocationCacheSeconds(), iam.getRevocationTimeoutMillis(),
                        iam.getRevocationFailureBackoffSeconds() * 1000L);
            }
        }
        java.util.Set<String> acceptedClients = iam.getOauthClientIds().stream()
                .map(String::trim).filter(id -> !id.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        DoroJwtAuthFilter filter = new DoroJwtAuthFilter(
                jwksKeyProvider,
                iam.getIssuer(),
                iam.getIssuerValidation(),
                iam.getCookieName(),
                iam.getClockSkewSeconds(),
                iam.getAudience(),
                revocationMode,
                revocationChecker,
                iam.isRevocationFailOpen(),
                acceptedClients);
        log.info("Doro JWT validation: issuer-validation={}, audience-check={}, cookie-auth={}, revocation-check={}, oauth-clients={}",
                iam.getIssuerValidation(),
                iam.getAudience() != null && !iam.getAudience().isBlank() ? "ENFORCE" : "OFF",
                iam.getCookieName() != null && !iam.getCookieName().isBlank(),
                revocationMode,
                acceptedClients.isEmpty() ? "none (OAuth client tokens rejected)" : acceptedClients);
        FilterRegistrationBean<DoroJwtAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "doro.guard", name = "enabled", havingValue = "true", matchIfMissing = true)
    public DoroGuardClient doroGuardClient(DoroProperties properties) {
        return new DoroGuardClient(
                properties.getGuard().getGrpcHost(),
                properties.getGuard().getGrpcPort(),
                3,
                properties.getGuard().getServiceToken()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public DoroGuardAspect doroGuardAspect(ObjectProvider<DoroGuardClient> doroGuardClient) {
        return new DoroGuardAspect(doroGuardClient.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication
    @ConditionalOnProperty(prefix = "doro.web", name = "exception-handler", havingValue = "true", matchIfMissing = true)
    public DoroExceptionHandlerAdvice doroExceptionHandlerAdvice() {
        return new DoroExceptionHandlerAdvice();
    }

    @Bean
    @ConditionalOnMissingBean
    public CurrentDoroUserArgumentResolver currentDoroUserArgumentResolver() {
        return new CurrentDoroUserArgumentResolver();
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentDoroUserArgumentResolver());
    }
}

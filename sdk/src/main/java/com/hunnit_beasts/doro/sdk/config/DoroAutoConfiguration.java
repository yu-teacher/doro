package com.hunnit_beasts.doro.sdk.config;

import com.hunnit_beasts.doro.sdk.aop.DoroGuardAspect;
import com.hunnit_beasts.doro.sdk.client.DoroGuardClient;
import com.hunnit_beasts.doro.sdk.security.filter.DoroJwtAuthFilter;
import com.hunnit_beasts.doro.sdk.security.jwks.JwksKeyProvider;
import com.hunnit_beasts.doro.sdk.web.CurrentDoroUserArgumentResolver;
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

@AutoConfiguration
@EnableAspectJAutoProxy
@EnableConfigurationProperties(DoroProperties.class)
public class DoroAutoConfiguration implements WebMvcConfigurer {

    @Bean
    @ConditionalOnMissingBean
    public JwksKeyProvider jwksKeyProvider(DoroProperties properties) {
        return new JwksKeyProvider(properties.getIam().getJwksUri());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication
    public FilterRegistrationBean<DoroJwtAuthFilter> doroJwtAuthFilterRegistration(JwksKeyProvider jwksKeyProvider) {
        DoroJwtAuthFilter filter = new DoroJwtAuthFilter(jwksKeyProvider);
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
                properties.getGuard().getGrpcPort()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public DoroGuardAspect doroGuardAspect(DoroGuardClient doroGuardClient) {
        return new DoroGuardAspect(doroGuardClient);
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

package com.hunnit_beasts.doro.sdk.config;

import com.hunnit_beasts.doro.sdk.bff.BffAuthService;
import com.hunnit_beasts.doro.sdk.bff.BffCleanup;
import com.hunnit_beasts.doro.sdk.bff.BffController;
import com.hunnit_beasts.doro.sdk.bff.BffCookies;
import com.hunnit_beasts.doro.sdk.bff.BffCrypto;
import com.hunnit_beasts.doro.sdk.bff.BffLoginAttemptStore;
import com.hunnit_beasts.doro.sdk.bff.BffOAuthClient;
import com.hunnit_beasts.doro.sdk.bff.BffProperties;
import com.hunnit_beasts.doro.sdk.bff.BffSessionAuthFilter;
import com.hunnit_beasts.doro.sdk.bff.BffSessionStore;
import com.hunnit_beasts.doro.sdk.bff.BffUserHook;
import com.hunnit_beasts.doro.sdk.security.DoroTokenVerifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

import java.time.Clock;

/**
 * BFF 로그인 자동 구성. 서비스가 {@link BffProperties}, {@link BffSessionStore}, {@link BffLoginAttemptStore} 빈을 등록하면 켜진다
 * (그 셋이 없는 서비스에는 아무 영향이 없다). 로그인 성공 훅({@link BffUserHook})은 선택이다.
 */
@AutoConfiguration(after = DoroAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean({BffProperties.class, BffSessionStore.class, BffLoginAttemptStore.class})
public class DoroBffAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public BffCrypto bffCrypto(BffProperties props) {
        return new BffCrypto(props.getSessionEncryptionKey());
    }

    @Bean
    @ConditionalOnMissingBean
    public BffOAuthClient bffOAuthClient(BffProperties props) {
        requireClientId(props);
        return new BffOAuthClient(props);
    }

    @Bean
    @ConditionalOnMissingBean
    public BffCookies bffCookies(BffProperties props) {
        return new BffCookies(props);
    }

    @Bean
    @ConditionalOnMissingBean
    public BffAuthService bffAuthService(BffProperties props, BffOAuthClient oauth, BffCrypto crypto, BffSessionStore sessions,
                                         BffLoginAttemptStore attempts, DoroTokenVerifier verifier,
                                         ObjectProvider<BffUserHook> userHook) {
        return new BffAuthService(props, oauth, crypto, sessions, attempts, verifier,
                userHook.getIfAvailable(BffUserHook::none), Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean
    public BffController bffController(BffAuthService authService, BffProperties props, BffCookies cookies) {
        return new BffController(authService, props, cookies);
    }

    @Bean
    public FilterRegistrationBean<BffSessionAuthFilter> bffSessionAuthFilter(BffAuthService authService, BffProperties props,
                                                                           BffCookies cookies) {
        FilterRegistrationBean<BffSessionAuthFilter> registration =
                new FilterRegistrationBean<>(new BffSessionAuthFilter(authService, props, cookies));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    public BffCleanup bffCleanup(BffSessionStore sessions, BffLoginAttemptStore attempts) {
        return new BffCleanup(sessions, attempts);
    }

    private static void requireClientId(BffProperties props) {
        if (props.getClientId() == null || props.getClientId().isBlank()) {
            throw new IllegalStateException("BFF 로그인에는 OAuth client-id 설정이 필요하다");
        }
    }
}

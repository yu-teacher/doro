package com.hunnit_beasts.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 이 서비스는 JWT 만 쓰고 폼/Basic 로그인을 끈다. Spring Boot 가 기본 사용자를 자동 생성하면
 * "Using generated security password" 로 비밀번호가 로그에 남으므로, 자동 생성이 일어나지 않아야 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class NoGeneratedDefaultUserTest {

    @Autowired
    private Map<String, UserDetailsService> userDetailsServices;

    @Test
    @DisplayName("자동 생성 기본 사용자(InMemoryUserDetailsManager)가 만들어지지 않는다")
    void noGeneratedInMemoryUser() {
        assertThat(userDetailsServices.values()).noneMatch(InMemoryUserDetailsManager.class::isInstance);
    }

    @Test
    @DisplayName("등록된 UserDetailsService 는 어떤 사용자도 찾아 주지 않는다")
    void userDetailsServiceRefusesEveryone() {
        assertThat(userDetailsServices).isNotEmpty();
        userDetailsServices.values().forEach(service ->
                assertThatThrownBy(() -> service.loadUserByUsername("user")).isInstanceOf(UsernameNotFoundException.class));
    }
}

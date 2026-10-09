package com.hunnit_beasts.auth;

import com.hunnit_beasts.auth.domain.auth.dto.SignUpRequest;
import com.hunnit_beasts.auth.domain.auth.service.AuthService;
import com.hunnit_beasts.auth.infrastructure.guard.GuardClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 이메일 대소문자 유일성(A6): 서비스 검사를 우회해도 DB 가 대소문자만 다른 중복·대문자 저장을 막는다.
 * 제약은 Flyway V9 가 만들므로 마이그레이션을 실행하는 PostgreSQL 단계(testOnPostgres)에서만 검증한다.
 * (H2 단위 테스트는 Hibernate 가 스키마를 만들어 V9 가 적용되지 않는다)
 */
@EnabledIfEnvironmentVariable(named = "TEST_PG_URL", matches = ".+")
@SpringBootTest
@ActiveProfiles("test")
class EmailLowercaseConstraintTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AuthService authService;
    @MockitoBean
    private GuardClient guardClient;

    private void insert(String email) {
        jdbc.update("INSERT INTO users (id, email, name, status, role, created_at, updated_at) VALUES (?, ?, 'n', 'ACTIVE', 'USER', now(), now())",
                UUID.randomUUID(), email);
    }

    @Test
    @DisplayName("대문자가 섞인 이메일은 DB 가 직접 삽입을 거부한다")
    void mixedCaseEmailRejectedByDatabase() {
        String email = "Mixed-" + UUID.randomUUID() + "@example.com";
        assertThatThrownBy(() -> insert(email)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE lower(email) = lower(?)", Integer.class, email)).isZero();
    }

    @Test
    @DisplayName("소문자 이메일은 정상 저장되고, 같은 이메일을 다시 넣으면 거부된다")
    void lowercaseUniqueEnforced() {
        String email = "lower-" + UUID.randomUUID() + "@example.com";
        insert(email);
        assertThatThrownBy(() -> insert(email)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("가입 API 는 대문자로 들어온 이메일을 소문자로 저장한다")
    void signupStoresLowercase() {
        String local = "Signup-" + UUID.randomUUID();
        authService.signup(new SignUpRequest(local + "@Example.COM", "Password123!", "이름"));
        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE email = ?", String.class, (local + "@example.com").toLowerCase()))
                .isEqualTo((local + "@example.com").toLowerCase());
    }
}

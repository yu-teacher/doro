package com.hunnit_beasts.auth;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.domain.auth.dto.SignUpRequest;
import com.hunnit_beasts.auth.domain.auth.service.AuthService;
import com.hunnit_beasts.auth.domain.credential.entity.Credential;
import com.hunnit_beasts.auth.domain.credential.repository.CredentialRepository;
import com.hunnit_beasts.auth.domain.user.dto.UserProfileResponse;
import com.hunnit_beasts.auth.domain.user.entity.User;
import com.hunnit_beasts.auth.domain.user.entity.UserRole;
import com.hunnit_beasts.auth.domain.user.repository.AdminUserFilter;
import com.hunnit_beasts.auth.domain.user.repository.UserRepository;
import com.hunnit_beasts.auth.domain.user.service.UserService;
import com.hunnit_beasts.auth.infrastructure.guard.GuardClient;
import com.hunnit_beasts.auth.interfaces.api.AdminController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** 관리자 사용자 목록의 서버 측 검색·필터·개수. 웹 화면이 브라우저에서 하던 일을 서버가 같은 의미로 해야 한다. */
@SpringBootTest
@ActiveProfiles("test")
class AdminUserListingTest {

    @Autowired private AuthService authService;
    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private CredentialRepository credentialRepository;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired private AdminController adminController;
    @MockitoBean private GuardClient guardClient;

    private final UUID admin = UUID.randomUUID();
    private String marker;

    @BeforeEach
    void seed() {
        when(guardClient.check(eq("system"), eq("doro"), eq("admin"), anyString())).thenReturn(true);
        marker = "lf" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

        UUID adminUser = signup("a", marker + " Alpha");
        UUID totpUser = signup("b", marker + " Bravo");
        UUID suspendedUser = signup("c", marker + " Charlie");
        UUID lockedUser = signup("d", marker + " Delta");
        signup("e", marker + " Echo");
        signup("f", marker + " 50%_off");

        User a = userRepository.findById(adminUser).orElseThrow();
        a.changeRole(UserRole.ADMIN);
        userRepository.save(a);

        Credential b = credentialRepository.findByUserId(totpUser).orElseThrow();
        b.updateTotpSecret("JBSWY3DPEHPK3PXP");
        credentialRepository.save(b);

        User c = userRepository.findById(suspendedUser).orElseThrow();
        c.suspend(Instant.now(), admin, "test");
        userRepository.save(c);

        jdbc.update("update credentials set locked_until = ?, failed_attempts = 30 where user_id = ?",
                java.sql.Timestamp.from(Instant.now().plusSeconds(3600)), lockedUser);
    }

    private UUID signup(String tag, String name) {
        return authService.signup(new SignUpRequest(marker + "-" + tag + "@doro.local", "Password123!", name));
    }

    private Page<UserProfileResponse> list(String q, AdminUserFilter filter, int page, int size) {
        return userService.getUsers(admin, PageRequest.of(page, size), q, filter);
    }

    @Test
    @DisplayName("검색어로 이름·이메일을 찾고, 페이지 합계와 총 개수가 맞는다")
    void searchAndPaging() {
        Page<UserProfileResponse> first = list(marker, AdminUserFilter.ALL, 0, 4);
        Page<UserProfileResponse> second = list(marker, AdminUserFilter.ALL, 1, 4);
        assertThat(first.getTotalElements()).isEqualTo(6);
        assertThat(first.getContent()).hasSize(4);
        assertThat(second.getContent()).hasSize(2);
        assertThat(first.getContent()).doesNotContainAnyElementsOf(second.getContent());
    }

    @Test
    @DisplayName("검색은 대소문자를 구분하지 않고 이메일 일부로도 찾는다")
    void searchIsCaseInsensitive() {
        assertThat(list(marker.toUpperCase(), AdminUserFilter.ALL, 0, 50).getTotalElements()).isEqualTo(6);
        assertThat(list(marker + "-c@doro", AdminUserFilter.ALL, 0, 50).getTotalElements()).isEqualTo(1);
        assertThat(list(marker + " charlie", AdminUserFilter.ALL, 0, 50).getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("검색어의 % 와 _ 는 와일드카드가 아니라 문자 그대로 찾는다")
    void likeWildcardsAreLiteral() {
        assertThat(list(marker + " 50%_", AdminUserFilter.ALL, 0, 50).getTotalElements()).isEqualTo(1);
        // '_' 가 한 글자 와일드카드였다면 "50" 과 맞아 1건이 나온다
        assertThat(list(marker + " 5_", AdminUserFilter.ALL, 0, 50).getTotalElements()).isZero();
        // '%' 가 와일드카드였다면 전부 맞는다
        assertThat(list(marker + " %", AdminUserFilter.ALL, 0, 50).getTotalElements()).isZero();
    }

    @Test
    @DisplayName("필터 탭이 화면의 기존 의미와 같다: 역할, 2FA, 정지·잠김")
    void filtersMatchTabs() {
        assertThat(list(marker, AdminUserFilter.ADMIN, 0, 50).getTotalElements()).isEqualTo(1);
        assertThat(list(marker, AdminUserFilter.SUPER_ADMIN, 0, 50).getTotalElements()).isZero();
        assertThat(list(marker, AdminUserFilter.USER, 0, 50).getTotalElements()).isEqualTo(5);
        assertThat(list(marker, AdminUserFilter.TOTP, 0, 50).getContent())
                .extracting(UserProfileResponse::email).containsExactly(marker + "-b@doro.local");
        assertThat(list(marker, AdminUserFilter.SUSPENDED, 0, 50).getContent())
                .extracting(UserProfileResponse::email)
                .containsExactlyInAnyOrder(marker + "-c@doro.local", marker + "-d@doro.local");
    }

    @Test
    @DisplayName("필터 결과는 응답의 hasTotp·locked·status 와 일치한다")
    void filteredRowsCarryConsistentFlags() {
        assertThat(list(marker, AdminUserFilter.TOTP, 0, 50).getContent()).allMatch(UserProfileResponse::hasTotp);
        List<UserProfileResponse> susp = list(marker, AdminUserFilter.SUSPENDED, 0, 50).getContent();
        assertThat(susp).allMatch(u -> "SUSPENDED".equals(u.status()) || u.locked());
    }

    @Test
    @DisplayName("요약 개수는 전체 목록을 직접 센 값과 같다")
    void summaryMatchesFullList() {
        UserService.AdminUserSummary s = userService.getUserSummary(admin);
        List<UserProfileResponse> all = userService.getAllUsers(admin);
        assertThat(s.total()).isEqualTo(all.size());
        assertThat(s.admins()).isEqualTo(all.stream().filter(u -> "ADMIN".equals(u.role())).count());
        assertThat(s.superAdmins()).isEqualTo(all.stream().filter(u -> "SUPER_ADMIN".equals(u.role())).count());
        assertThat(s.users()).isEqualTo(all.stream().filter(u -> "USER".equals(u.role())).count());
        assertThat(s.totp()).isEqualTo(all.stream().filter(UserProfileResponse::hasTotp).count());
        assertThat(s.suspendedOrLocked())
                .isEqualTo(all.stream().filter(u -> "SUSPENDED".equals(u.status()) || u.locked()).count());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("컨트롤러: 잘못된 filter·너무 긴 q 는 거부하고, 검색은 X-Total-Count 를 준다")
    void controllerValidation() {
        assertThatThrownBy(() -> adminController.getAllUsers(admin, 0, 10, null, "NOPE")).isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> adminController.getAllUsers(admin, 0, 10, "x".repeat(101), null)).isInstanceOf(AuthException.class);

        var res = adminController.getAllUsers(admin, 0, 10, marker, "TOTP");
        assertThat(res.getHeaders().getFirst("X-Total-Count")).isEqualTo("1");
        assertThat(adminController.getAllUsers(admin, null, null, marker, null).getHeaders().getFirst("X-Total-Count")).isEqualTo("6");
    }

    @Test
    @DisplayName("요약도 Guard 가 거부하면 조회할 수 없다")
    void summaryIsGuarded() {
        when(guardClient.check(eq("system"), eq("doro"), eq("admin"), anyString())).thenReturn(false);
        assertThatThrownBy(() -> userService.getUserSummary(UUID.randomUUID())).isInstanceOf(AuthException.class);
    }
}

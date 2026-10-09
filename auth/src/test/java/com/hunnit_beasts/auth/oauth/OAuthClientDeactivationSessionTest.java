package com.hunnit_beasts.auth.oauth;

import com.hunnit_beasts.auth.common.exception.AuthException;
import com.hunnit_beasts.auth.domain.auth.dto.SignUpRequest;
import com.hunnit_beasts.auth.domain.auth.service.AuthService;
import com.hunnit_beasts.auth.domain.oauth.dto.OAuthClientCreateRequest;
import com.hunnit_beasts.auth.domain.oauth.service.OAuth2Constants;
import com.hunnit_beasts.auth.domain.oauth.service.OAuthClientAdminService;
import com.hunnit_beasts.auth.domain.session.entity.UserSession;
import com.hunnit_beasts.auth.domain.session.service.SessionService;
import com.hunnit_beasts.auth.infrastructure.guard.GuardClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * OAuth 클라이언트를 비활성화하면 이미 발급된 그 클라이언트의 세션도 끝나야 한다.
 * (새 인가·토큰 요청은 막히지만 살아 있는 세션과 액세스 토큰은 그대로 남아 있었다)
 */
@SpringBootTest
@ActiveProfiles("test")
class OAuthClientDeactivationSessionTest {

    @Autowired private OAuthClientAdminService adminService;
    @Autowired private SessionService sessionService;
    @Autowired private AuthService authService;
    @MockitoBean private GuardClient guardClient;

    private UUID newUser() {
        return authService.signup(new SignUpRequest("deact-" + UUID.randomUUID() + "@doro.local", "Password123!", "Deact"));
    }

    private String registerClient() {
        when(guardClient.check(eq("system"), eq("doro"), eq("admin"), anyString())).thenReturn(true);
        String clientId = "deact-" + UUID.randomUUID().toString().substring(0, 12);
        adminService.create(UUID.randomUUID(), new OAuthClientCreateRequest("앱", List.of("https://app.example.com/cb"), null, clientId, false));
        return clientId;
    }

    private UserSession oauthSession(UUID userId, String clientId) {
        return sessionService.createSession(userId, OAuth2Constants.SESSION_DEVICE_PREFIX + clientId,
                OAuth2Constants.SESSION_IP_MARKER, OAuth2Constants.SESSION_USER_AGENT_PREFIX + clientId);
    }

    @Test
    @DisplayName("클라이언트를 비활성화하면 그 클라이언트의 모든 사용자 세션이 끝나고, 다른 클라이언트와 일반 로그인 세션은 유지된다")
    void deactivatingAClientEndsItsSessions() {
        String target = registerClient();
        String other = registerClient();
        UUID userA = newUser();
        UUID userB = newUser();
        UserSession a = oauthSession(userA, target);
        UserSession b = oauthSession(userB, target);
        UserSession keepOther = oauthSession(userA, other);
        UserSession web = sessionService.createSession(userA, "Chrome", "10.0.0.1", "Mozilla/5.0");

        adminService.deactivate(UUID.randomUUID(), target);

        assertThatThrownBy(() -> sessionService.assertSessionLive(a.getId())).isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> sessionService.assertSessionLive(b.getId())).isInstanceOf(AuthException.class);
        sessionService.assertSessionLive(keepOther.getId());
        sessionService.assertSessionLive(web.getId());
    }
}

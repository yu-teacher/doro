package com.hunnit_beasts.auth.oauth;

import com.hunnit_beasts.auth.domain.oauth.entity.OAuthClient;
import com.hunnit_beasts.auth.infrastructure.guard.GuardClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 자사(first-party) 클라이언트 표시와 포털 동의 화면용 클라이언트 정보 엔드포인트. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OAuthClientInfoTest extends OAuthTestSupport {

    @MockitoBean
    private GuardClient guardClient;

    private OAuthClient register(String clientId, boolean firstParty) {
        Set<String> scopes = new LinkedHashSet<>(List.of("openid", "profile"));
        return clientRepository.save(OAuthClient.builder()
                .clientId(clientId).name("Blog").redirectUris(List.of(GOOD_REDIRECT))
                .allowedScopes(scopes).firstParty(firstParty).build());
    }

    @Test
    @DisplayName("자사 클라이언트는 firstParty=true, 일반 클라이언트는 false 로 보인다")
    void reportsFirstPartyFlag() throws Exception {
        Login login = signupAndLogin();
        String mine = uniqueClientId();
        String other = uniqueClientId();
        register(mine, true);
        register(other, false);

        mockMvc.perform(get("/oauth2/client-info").param("client_id", mine)
                        .header("Authorization", "Bearer " + login.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clientId").value(mine))
                .andExpect(jsonPath("$.data.name").value("Blog"))
                .andExpect(jsonPath("$.data.firstParty").value(true));
        mockMvc.perform(get("/oauth2/client-info").param("client_id", other)
                        .header("Authorization", "Bearer " + login.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.firstParty").value(false));
    }

    @Test
    @DisplayName("등록되지 않았거나 비활성인 클라이언트는 404 (존재 여부를 알리지 않는다)")
    void unknownAndDeactivatedClientsAreNotFound() throws Exception {
        Login login = signupAndLogin();
        String deactivated = uniqueClientId();
        OAuthClient client = register(deactivated, true);
        client.deactivate();
        clientRepository.saveAndFlush(client);

        mockMvc.perform(get("/oauth2/client-info").param("client_id", uniqueClientId())
                .header("Authorization", "Bearer " + login.accessToken())).andExpect(status().isNotFound());
        mockMvc.perform(get("/oauth2/client-info").param("client_id", deactivated)
                .header("Authorization", "Bearer " + login.accessToken())).andExpect(status().isNotFound());
        mockMvc.perform(get("/oauth2/client-info").param("client_id", "bad id!")
                .header("Authorization", "Bearer " + login.accessToken())).andExpect(status().isNotFound());
        mockMvc.perform(get("/oauth2/client-info")
                .header("Authorization", "Bearer " + login.accessToken())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("로그인하지 않으면 조회할 수 없다 (401)")
    void requiresLogin() throws Exception {
        String clientId = uniqueClientId();
        register(clientId, true);

        mockMvc.perform(get("/oauth2/client-info").param("client_id", clientId)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("관리자 API 로 firstParty 클라이언트를 등록하면 응답과 정보 조회에 반영된다 (생략하면 false)")
    void adminCanRegisterFirstPartyClient() throws Exception {
        when(guardClient.check(eq("system"), eq("doro"), eq("admin"), anyString())).thenReturn(true);
        Login admin = signupAndLogin();
        jdbcTemplate.update("update users set role = 'ADMIN' where email = ?", admin.email());
        String adminToken = login(admin.email()).accessToken();
        String clientId = uniqueClientId();

        mockMvc.perform(post("/api/v1/admin/oauth/clients").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "My Blog", "clientId", clientId, "redirectUris", List.of(GOOD_REDIRECT), "firstParty", true))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.firstParty").value(true));
        mockMvc.perform(post("/api/v1/admin/oauth/clients").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Third", "redirectUris", List.of(GOOD_REDIRECT)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.firstParty").value(false));
    }
}

package com.hunnit_beasts.auth.oauth;

import com.hunnit_beasts.auth.domain.oauth.entity.OAuthClient;
import com.hunnit_beasts.auth.domain.oauth.service.ClientRegistryMode;
import com.hunnit_beasts.auth.domain.oauth.service.OAuthClientRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 레지스트리 모드(OFF / WARN / ENFORCE)별 동작. */
class OAuthRegistryModeTest {

    private static final String CHALLENGE = "E9Melhoa2OwvFrGMTJguCH5rtx64FIbEIqiPQsjzkxo";

    @SpringBootTest(properties = "doro.oauth.client-registry-mode=ENFORCE")
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    static class Enforce extends OAuthTestSupport {

        @Test
        @DisplayName("ENFORCE: 미등록 client_id 는 환경변수 허용 목록에 있어도 거부(인가 400, 토큰 401 invalid_client)")
        void unregisteredClientIsRejected() throws Exception {
            Login me = signupAndLogin();
            mockMvc.perform(authorizeRequest(me, "legacy-client", "https://app-a.com/cb", CHALLENGE))
                    .andExpect(status().isBadRequest());

            MvcResult token = mockMvc.perform(codeExchangeForm("whatever", "legacy-client", "https://app-a.com/cb", newPkce().verifier()))
                    .andReturn();
            assertThat(token.getResponse().getStatus()).isEqualTo(401);
            assertThat(json(token).path("error").asText()).isEqualTo("invalid_client");
        }

        @Test
        @DisplayName("ENFORCE: 등록된 활성 클라이언트는 전체 흐름이 동작하고, 비활성화하면 즉시 거부된다")
        void registeredClientWorksUntilDeactivated() throws Exception {
            String clientId = uniqueClientId();
            OAuthClient client = registerClient(clientId, List.of(GOOD_REDIRECT));
            Login me = signupAndLogin();

            Pkce pkce = newPkce();
            String code = authorizeCode(me, clientId, GOOD_REDIRECT, pkce, "openid", null);
            mockMvc.perform(codeExchangeForm(code, clientId, GOOD_REDIRECT, pkce.verifier())).andExpect(status().isOk());

            client.deactivate();
            clientRepository.save(client);
            mockMvc.perform(authorizeRequest(me, clientId, GOOD_REDIRECT, CHALLENGE)).andExpect(status().isBadRequest());
            MvcResult token = mockMvc.perform(codeExchangeForm("x", clientId, GOOD_REDIRECT, pkce.verifier())).andReturn();
            assertThat(token.getResponse().getStatus()).isEqualTo(401);
        }
    }

    @SpringBootTest(properties = "doro.oauth.client-registry-mode=OFF")
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    static class Off extends OAuthTestSupport {

        @Test
        @DisplayName("OFF: 레지스트리를 무시하고 환경변수 허용 목록만 사용한다(등록 클라이언트의 URI 도 적용되지 않음)")
        void onlyEnvAllowlistApplies() throws Exception {
            String clientId = uniqueClientId();
            registerClient(clientId, List.of(GOOD_REDIRECT));
            Login me = signupAndLogin();

            mockMvc.perform(authorizeRequest(me, clientId, GOOD_REDIRECT, CHALLENGE)).andExpect(status().isBadRequest());
            mockMvc.perform(authorizeRequest(me, clientId, "https://app-a.com/cb", CHALLENGE))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.code").isNotEmpty());
        }
    }

    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    static class Warn extends OAuthTestSupport {

        @Autowired
        private OAuthClientRegistry registry;

        @Test
        @DisplayName("WARN(기본): 등록 클라이언트는 자기 URI 만, 미등록은 허용 목록 폴백")
        void registeredStrictUnregisteredFallsBack() throws Exception {
            String clientId = uniqueClientId();
            registerClient(clientId, List.of(GOOD_REDIRECT));
            Login me = signupAndLogin();

            assertThat(registry.mode()).isEqualTo(ClientRegistryMode.WARN);
            mockMvc.perform(authorizeRequest(me, clientId, GOOD_REDIRECT, CHALLENGE)).andExpect(status().isOk());
            mockMvc.perform(authorizeRequest(me, clientId, "https://app-a.com/cb", CHALLENGE)).andExpect(status().isBadRequest());
            mockMvc.perform(authorizeRequest(me, "unregistered-app", "https://app-a.com/cb", CHALLENGE)).andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("모드 문자열 파싱")
    class Parsing {
        @Test
        @DisplayName("대소문자 무시, 비어 있으면 WARN, 알 수 없는 값은 기동 실패")
        void parse() {
            assertThat(ClientRegistryMode.parse("enforce")).isEqualTo(ClientRegistryMode.ENFORCE);
            assertThat(ClientRegistryMode.parse(" off ")).isEqualTo(ClientRegistryMode.OFF);
            assertThat(ClientRegistryMode.parse("")).isEqualTo(ClientRegistryMode.WARN);
            assertThatThrownBy(() -> ClientRegistryMode.parse("strict")).isInstanceOf(IllegalStateException.class);
        }
    }
}

package com.hunnit_beasts.auth;

import com.hunnit_beasts.auth.infrastructure.guard.GuardClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 인증 없이 부를 수 있는 계정 조회(lookup)는 큰 프로필 이미지를 돌려주지 않는다(대역폭·개인정보 노출을 줄인다). */
@SpringBootTest(properties = "doro.iam.lookup.max-image-length=2000")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LookupImageLimitTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private GuardClient guardClient;

    private String newUser(String image) throws Exception {
        String email = "lookup-img-" + UUID.randomUUID() + "@doro.local";
        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"Password123!\",\"name\":\"Lookup User\"}"));
        jdbc.update("update users set profile_image_url = ? where email = ?", image, email);
        return email;
    }

    private org.springframework.test.web.servlet.ResultActions lookup(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/lookup").contentType(MediaType.APPLICATION_JSON)
                .header("X-Real-IP", "198.51.100." + (1 + (int) (Math.random() * 200)))
                .content("{\"email\":\"" + email + "\"}"));
    }

    @Test
    @DisplayName("상한 이하의 이미지는 그대로, 넘는 이미지는 생략하고 이름은 그대로 돌려준다")
    void capsTheImageSize() throws Exception {
        String small = "data:image/jpeg;base64," + "A".repeat(500);
        String big = "data:image/jpeg;base64," + "A".repeat(5000);

        lookup(newUser(small)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profileImageUrl").value(small)).andExpect(jsonPath("$.data.name").value("Lookup User"));
        lookup(newUser(big)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profileImageUrl").doesNotExist()).andExpect(jsonPath("$.data.name").value("Lookup User"));
        lookup(newUser(null)).andExpect(status().isOk()).andExpect(jsonPath("$.data.profileImageUrl").doesNotExist());
    }
}

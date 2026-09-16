package com.hunnit_beasts.guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunnit_beasts.guard.domain.tuple.dto.TupleDto;
import com.hunnit_beasts.guard.interfaces.api.dto.GuardApiDtos.CheckApiRequest;
import com.hunnit_beasts.guard.interfaces.api.dto.GuardApiDtos.SchemaRegisterRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GuardIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.hunnit_beasts.guard.domain.tuple.repository.RelationTupleRepository tupleRepository;

    @Autowired
    private com.hunnit_beasts.guard.core.engine.CheckEngine checkEngine;

    @Autowired
    private com.hunnit_beasts.guard.core.dsl.service.SchemaService schemaService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        tupleRepository.deleteAll();
        checkEngine.invalidateCache();
        schemaService.resetToDefault();
    }

    @Test
    @DisplayName("통합 E2E 테스트: 스키마 동적 등록 ➡️ 튜플 입력 ➡️ Check 권한 승인 ➡️ 튜플 삭제 ➡️ 권한 거부")
    void testGuardFullLifecycle() throws Exception {
        // 1. 스키마 동적 등록
        String dsl = """
                type user {}
                type workspace {
                  relation admin: user
                  relation member: user | admin
                }
                """;

        mockMvc.perform(post("/api/v1/guard/schema")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SchemaRegisterRequest(dsl))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // 2. 튜플 등록: workspace:ws-100#admin@user:alice
        List<TupleDto> tuples = List.of(
                TupleDto.of("workspace", "ws-100", "admin", "user", "alice")
        );

        mockMvc.perform(post("/api/v1/guard/tuples")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tuples)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.writtenCount").value(1));

        // 3. Check 권한 검사: alice는 admin이므로 member 권한도 자동 승인
        CheckApiRequest checkReq = new CheckApiRequest(
                "workspace", "ws-100", "member", "user", "alice", null
        );

        mockMvc.perform(post("/api/v1/guard/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(checkReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allowed").value(true));

        // 4. 튜플 삭제
        mockMvc.perform(delete("/api/v1/guard/tuples")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tuples)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deletedCount").value(1));

        // 5. 삭제 후 Check 재검사: 권한 거부 (allowed: false)
        mockMvc.perform(post("/api/v1/guard/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(checkReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allowed").value(false));
    }
}

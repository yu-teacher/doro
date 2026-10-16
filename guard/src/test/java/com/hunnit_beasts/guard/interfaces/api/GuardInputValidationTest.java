package com.hunnit_beasts.guard.interfaces.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** G3: REST 입력 검증. 배열 본문(@Valid List) 위반도 500 이 아니라 400 INVALID_INPUT_VALUE 여야 한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GuardInputValidationTest {

    @Autowired
    private MockMvc mockMvc;

    private static String tupleJson(String namespace, String objectId) {
        return "[{\"namespace\":\"" + namespace + "\",\"objectId\":\"" + objectId + "\",\"relation\":\"owner\","
                + "\"subjectNamespace\":\"user\",\"subjectId\":\"u1\"}]";
    }

    @Test
    @DisplayName("POST /tuples: 배열 안에 빈 필드가 있으면 400 INVALID_INPUT_VALUE (500 아님)")
    void blankFieldInTupleArrayIs400() throws Exception {
        mockMvc.perform(post("/api/v1/guard/tuples").contentType(MediaType.APPLICATION_JSON)
                        .content(tupleJson("", "doc1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"))
                .andExpect(jsonPath("$.success").doesNotExist());
    }

    @Test
    @DisplayName("DELETE /tuples: 배열 안에 빈 필드가 있으면 400 INVALID_INPUT_VALUE")
    void blankFieldInDeleteArrayIs400() throws Exception {
        mockMvc.perform(delete("/api/v1/guard/tuples").contentType(MediaType.APPLICATION_JSON)
                        .content(tupleJson("document", " ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"));
    }

    @Test
    @DisplayName("POST /tuples: 컬럼 길이(64/128)를 넘으면 DB 오류(500) 대신 400")
    void overlongFieldsAre400() throws Exception {
        mockMvc.perform(post("/api/v1/guard/tuples").contentType(MediaType.APPLICATION_JSON)
                        .content(tupleJson("n".repeat(65), "doc1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"));
        mockMvc.perform(post("/api/v1/guard/tuples").contentType(MediaType.APPLICATION_JSON)
                        .content(tupleJson("document", "i".repeat(129))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"));
    }

    @Test
    @DisplayName("POST /check: 길이 초과는 400 INVALID_INPUT_VALUE")
    void overlongCheckIs400() throws Exception {
        mockMvc.perform(post("/api/v1/guard/check").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"namespace\":\"document\",\"objectId\":\"d\",\"relation\":\"" + "r".repeat(65)
                                + "\",\"subjectNamespace\":\"user\",\"subjectId\":\"u\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"));
    }

    private static String tuplesJson(int count) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"namespace\":\"document\",\"objectId\":\"cap-").append(i)
                    .append("\",\"relation\":\"owner\",\"subjectNamespace\":\"user\",\"subjectId\":\"u1\"}");
        }
        return sb.append(']').toString();
    }

    @Test
    @DisplayName("튜플 배치 상한: 1000개는 통과, 1001개는 400 INVALID_INPUT_VALUE (쓰기/삭제 모두)")
    void tupleBatchCapBoundary() throws Exception {
        mockMvc.perform(delete("/api/v1/guard/tuples").contentType(MediaType.APPLICATION_JSON)
                        .content(tuplesJson(1000)))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/guard/tuples").contentType(MediaType.APPLICATION_JSON)
                        .content(tuplesJson(1001)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"));
        mockMvc.perform(post("/api/v1/guard/tuples").contentType(MediaType.APPLICATION_JSON)
                        .content(tuplesJson(1001)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"));
    }

    @Test
    @DisplayName("스키마 DSL 상한: 100,000자를 넘으면 400 INVALID_INPUT_VALUE")
    void dslLengthCap() throws Exception {
        String tooLong = "#".repeat(100_001);
        mockMvc.perform(post("/api/v1/guard/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dsl\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"));
    }
}

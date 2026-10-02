package com.hunnit_beasts.guard.interfaces.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** G8: micrometer-registry-prometheus 가 있어야 /actuator/prometheus 가 존재한다. (@SpringBootTest 는 메트릭 내보내기를 기본 비활성화하므로 켠다.) */
@SpringBootTest(properties = {
        "management.defaults.metrics.export.enabled=true",
        "management.prometheus.metrics.export.enabled=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PrometheusEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("/actuator/prometheus 는 200 과 Prometheus 텍스트 형식 지표를 반환한다")
    void prometheusEndpointIsExposed() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("jvm_memory_used_bytes")));
    }

    @Test
    @DisplayName("Guard 는 Redis 를 쓰지 않으므로 Redis 스타터가 클래스패스에 없다 (Redis 장애가 헬스에 영향을 주지 않는다)")
    void redisIsNotOnTheClasspath() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> Class.forName("org.springframework.data.redis.core.RedisTemplate"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}

package com.hunnit_beasts.auth;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hunnit_beasts.auth.config.AuthRateLimitFilter;
import com.hunnit_beasts.auth.core.token.JwtAuthenticationFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 만료/잘못된 JWT 와 429 가 요청마다 WARN(스택트레이스 포함)으로 로그를 폭주시키지 않는지 확인한다. */
@SpringBootTest(properties = {
        "doro.iam.rate-limit.enabled=true",
        "doro.iam.rate-limit.login-max=1",
        "doro.oauth.warn-log-interval-seconds=3600"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthLogFloodTest {

    @Autowired
    private MockMvc mockMvc;

    private static ListAppender<ILoggingEvent> capture(Class<?> type) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(type)).addAppender(appender);
        return appender;
    }

    @Test
    @DisplayName("잘못된 JWT 를 반복해서 보내도 WARN 은 한 번만, 스택트레이스 없이 남는다")
    void invalidJwtLogsOnceWithoutStackTrace() throws Exception {
        ListAppender<ILoggingEvent> logs = capture(JwtAuthenticationFilter.class);
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer not.a.jwt"));
        }
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).hasSizeLessThanOrEqualTo(1);
        assertThat(logs.list).allMatch(e -> e.getThrowableProxy() == null);
    }

    @Test
    @DisplayName("429 를 반복해도 같은 버킷의 WARN 은 한 번만 남는다(응답은 매번 429)")
    void rateLimitWarnIsGated() throws Exception {
        ListAppender<ILoggingEvent> logs = capture(AuthRateLimitFilter.class);
        int tooMany = 0;
        for (int i = 0; i < 6; i++) {
            int status = mockMvc.perform(post("/api/v1/auth/login")
                            .header("X-Real-IP", "203.0.113.77")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"nobody@doro.local\",\"password\":\"Password123!\"}"))
                    .andReturn().getResponse().getStatus();
            if (status == 429) {
                tooMany++;
            }
        }
        assertThat(tooMany).isGreaterThanOrEqualTo(4);
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).hasSize(1);
    }
}

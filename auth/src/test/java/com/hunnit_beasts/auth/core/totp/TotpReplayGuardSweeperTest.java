package com.hunnit_beasts.auth.core.totp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TotpReplayGuardSweeperTest {

    @Test
    @DisplayName("A4: 드리프트 창을 벗어난 스텝 기록만 제거하고, 아직 재사용 방지가 필요한 기록은 남긴다")
    void purgesOnlyStepsOutsideTheDriftWindow() {
        TotpService totp = new TotpService();
        @SuppressWarnings("unchecked")
        Map<UUID, Long> map = (Map<UUID, Long>) ReflectionTestUtils.getField(totp, "lastUsedStepByUser");
        Instant now = Instant.now();
        long currentStep = now.getEpochSecond() / 30;
        UUID stale = UUID.randomUUID();
        UUID previousStep = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        map.put(stale, currentStep - 2);
        map.put(previousStep, currentStep - 1);
        map.put(current, currentStep);

        assertThat(totp.purgeStaleUsedSteps(now)).isEqualTo(1);
        assertThat(map).containsOnlyKeys(previousStep, current);
        assertThat(totp.trackedUserCount()).isEqualTo(2);
    }
}

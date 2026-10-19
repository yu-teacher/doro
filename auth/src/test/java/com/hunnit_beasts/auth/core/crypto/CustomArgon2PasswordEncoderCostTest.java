package com.hunnit_beasts.auth.core.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 해시 비용은 설정으로 바꿀 수 있지만 기본값은 운영 값(64MB·3회)이고, 운영이 약한 값으로 뜨는 실수는 기동 단계에서 막는다.
 * 테스트 스위트는 test 프로필에서만 비용을 낮춰 쓴다.
 */
class CustomArgon2PasswordEncoderCostTest {

    private static CustomArgon2PasswordEncoder build(MockEnvironment env, int memoryKb, int iterations) {
        return new CustomArgon2PasswordEncoder(8, 32, 3000, memoryKb, iterations, env);
    }

    @Test
    @DisplayName("기본 설정(생성자 기본값)은 운영 값이다: 메모리 64MB, 반복 3회")
    void defaultsAreTheProductionCost() {
        String hash = new CustomArgon2PasswordEncoder().encode("Password123!");

        assertThat(hash).startsWith("$argon2id$").contains("m=65536,t=3,p=1");
    }

    @Test
    @DisplayName("운영 프로필에서 설정 값을 주지 않으면 운영 값으로 뜬다")
    void productionProfileWithoutOverridesUsesProductionCost() {
        CustomArgon2PasswordEncoder encoder = build(new MockEnvironment(), CustomArgon2PasswordEncoder.DEFAULT_MEMORY_KB, CustomArgon2PasswordEncoder.DEFAULT_ITERATIONS);

        assertThat(encoder.encode("Password123!")).contains("m=65536,t=3,p=1");
    }

    @Test
    @DisplayName("운영(test 가 아닌 프로필)에서 메모리나 반복 횟수를 최소선보다 낮추면 기동이 실패한다")
    void weakCostIsRejectedOutsideTheTestProfile() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        assertThatThrownBy(() -> build(prod, 1024, 3)).isInstanceOf(IllegalStateException.class).hasMessageContaining("너무 약하다");
        assertThatThrownBy(() -> build(prod, 65536, 1)).isInstanceOf(IllegalStateException.class).hasMessageContaining("너무 약하다");
        assertThatThrownBy(() -> build(new MockEnvironment(), 8, 1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("최소선(19MiB·2회)과 그 이상은 운영에서도 받는다")
    void minimumCostIsAcceptedInProduction() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        String hash = build(prod, CustomArgon2PasswordEncoder.MIN_MEMORY_KB, CustomArgon2PasswordEncoder.MIN_ITERATIONS).encode("Password123!");

        assertThat(hash).contains("m=19456,t=2,p=1");
    }

    @Test
    @DisplayName("test 프로필에서는 낮은 비용을 받고, 그 해시도 올바르게 검증되며 틀린 비밀번호는 거부한다")
    void testProfileMayLowerTheCost() {
        MockEnvironment test = new MockEnvironment();
        test.setActiveProfiles("test");
        CustomArgon2PasswordEncoder encoder = build(test, 1024, 1);

        String hash = encoder.encode("Password123!");

        assertThat(hash).contains("m=1024,t=1,p=1");
        assertThat(encoder.matches("Password123!", hash)).isTrue();
        assertThat(encoder.matches("wrong-password", hash)).isFalse();
    }

    @Test
    @DisplayName("비용을 낮춘 설정이어도 운영 비용으로 만든 기존 해시는 그대로 검증된다(해시에 매개변수가 들어 있다)")
    void existingProductionHashesStillVerifyWithALowerConfiguredCost() {
        String productionHash = new CustomArgon2PasswordEncoder().encode("Password123!");
        MockEnvironment test = new MockEnvironment();
        test.setActiveProfiles("test");

        CustomArgon2PasswordEncoder cheap = build(test, 1024, 1);

        assertThat(cheap.matches("Password123!", productionHash)).isTrue();
    }
}

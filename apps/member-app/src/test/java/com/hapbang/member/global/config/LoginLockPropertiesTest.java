package com.hapbang.member.global.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LoginLockPropertiesTest {

    @Test
    @DisplayName("이메일 전체 상한이 IP별 횟수보다 작으면 시작하지 않는다 (IP 하나로 이메일 전체를 잠글 수 있게 되므로, #506)")
    void emailMaxFailuresAtLeastMaxFailures() {
        assertThatThrownBy(() -> properties(5, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email-max-failures");
        assertThatCode(() -> properties(5, 5)).doesNotThrowAnyException();
    }

    private static LoginLockProperties properties(int maxFailures, int emailMaxFailures) {
        return new LoginLockProperties(maxFailures, Duration.ofMinutes(15), emailMaxFailures, Duration.ofHours(1),
                Duration.ofDays(30));
    }
}

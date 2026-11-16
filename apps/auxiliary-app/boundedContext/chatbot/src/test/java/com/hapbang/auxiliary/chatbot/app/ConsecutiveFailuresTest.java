package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConsecutiveFailuresTest {

    private final ConsecutiveFailures failures = new ConsecutiveFailures();

    @Test
    @DisplayName("정한 횟수부터 알릴 차례이고, 성공하면 처음부터 다시 센다")
    void alertsFromThresholdAndResetsOnSuccess() {
        assertThat(failures.failed(3)).isEqualTo(new ConsecutiveFailures.Failure(1, false));
        assertThat(failures.failed(3)).isEqualTo(new ConsecutiveFailures.Failure(2, false));
        assertThat(failures.failed(3)).isEqualTo(new ConsecutiveFailures.Failure(3, true));
        assertThat(failures.failed(3)).isEqualTo(new ConsecutiveFailures.Failure(4, true));

        failures.succeeded();

        assertThat(failures.failed(3)).isEqualTo(new ConsecutiveFailures.Failure(1, false));
    }
}

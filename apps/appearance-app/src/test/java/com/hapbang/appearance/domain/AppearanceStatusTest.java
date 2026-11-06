package com.hapbang.appearance.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.hapbang.shared.appearance.domain.AppearanceResult;

/** 출연 상태 → 다른 모듈에 알리는 공개 결과 (#713) */
class AppearanceStatusTest {

    private static final Map<AppearanceStatus, AppearanceResult> EXPECTED = new EnumMap<>(Map.of(
            AppearanceStatus.ESTABLISHED, AppearanceResult.SCHEDULED,
            AppearanceStatus.CANCEL_REQUESTED, AppearanceResult.SCHEDULED,
            AppearanceStatus.CONFIRMED, AppearanceResult.COMPLETED,
            AppearanceStatus.CANCELED_BY_HOST, AppearanceResult.CANCELED,
            AppearanceStatus.CANCELED_BY_AGREEMENT, AppearanceResult.CANCELED,
            AppearanceStatus.ABSENT, AppearanceResult.NO_SHOW));

    @ParameterizedTest
    @EnumSource(AppearanceStatus.class)
    @DisplayName("모든 상태가 정해진 공개 결과로 바뀐다 (상태를 더하면 이 표에도 더해야 통과한다)")
    void everyStatus(AppearanceStatus status) {
        assertThat(EXPECTED).containsKey(status);
        assertThat(status.result()).isEqualTo(EXPECTED.get(status));
    }

    @Test
    @DisplayName("공개 결과 4가지가 모두 쓰인다")
    void everyResultUsed() {
        assertThat(EXPECTED.values()).containsOnly(AppearanceResult.values());
    }
}

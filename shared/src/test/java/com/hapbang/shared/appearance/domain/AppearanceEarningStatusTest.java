package com.hapbang.shared.appearance.domain;

import static com.hapbang.shared.appearance.domain.AppearanceEarningStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 출연 수익 상태 계약 (#301). 상수 이름은 강의와 같고(docs/api/internal-earnings.md), 요약에 넣는 상태를 고정한다.
class AppearanceEarningStatusTest {

    @Test
    @DisplayName("상태 이름은 강의 수익 상태와 같은 7개다 (HTTP 응답 문자열)")
    void sameNamesAsLecture() {
        assertThat(Arrays.stream(values()).map(Enum::name)).containsExactly("CONFIRMATION_PENDING", "SCHEDULED",
                "ON_HOLD", "PROCESSING", "FAILED", "COMPLETED", "CANCELED");
    }

    @Test
    @DisplayName("지급 완료와 취소만 미지급이 아니다")
    void unpaid() {
        assertThat(Arrays.stream(values()).filter(AppearanceEarningStatus::isUnpaid))
                .containsExactly(CONFIRMATION_PENDING, SCHEDULED, ON_HOLD, PROCESSING, FAILED);
    }
}

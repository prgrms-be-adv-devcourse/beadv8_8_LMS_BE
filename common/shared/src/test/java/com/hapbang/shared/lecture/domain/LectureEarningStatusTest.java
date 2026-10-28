package com.hapbang.shared.lecture.domain;

import static com.hapbang.shared.lecture.domain.LectureEarningStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

// 수익 상태 계약 (#300). 요약에 넣는 상태와 지급 예정 시각을 주는 상태를 고정한다.
class LectureEarningStatusTest {

    @Test
    void 지급_완료와_취소만_미지급이_아니다() {
        assertThat(Arrays.stream(values()).filter(LectureEarningStatus::isUnpaid))
                .containsExactly(CONFIRMATION_PENDING, SCHEDULED, ON_HOLD, PROCESSING, FAILED);
    }

    @Test
    void 지급_중단_실패_취소는_지급_예정_시각이_없다() {
        assertThat(Arrays.stream(values()).filter(status -> !status.hasPayoutSchedule()))
                .containsExactly(ON_HOLD, FAILED, CANCELED);
    }
}

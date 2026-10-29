package com.hapbang.lecture.domain;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

// 사용자에게 보여 줄 처리 상태는 내부 작업 상태가 아니라 업무 의미다 (module.md 처리 상태, D65)
class ProcessingStatusTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 12, 0);

    @Test
    void 처리_상태는_실패_전_PROCESSING_실패_후_DELAYED_DEAD면_ACTION_REQUIRED_끝나면_없다() {
        LectureHpTask task = LectureHpTask.create(LectureHpTaskPurpose.CANCEL, "LECTURE-CANCEL-31", 3L, 31L, 7L, null,
                10_000L, NOW);
        assertThat(ProcessingStatus.of(task)).isEqualTo(ProcessingStatus.PROCESSING);

        task.scheduleRetry("UNKNOWN_RESULT", NOW.plusMinutes(1), NOW);
        assertThat(ProcessingStatus.of(task)).isEqualTo(ProcessingStatus.DELAYED);

        task.markDead("UNKNOWN_RESULT", NOW);
        assertThat(ProcessingStatus.of(task)).isEqualTo(ProcessingStatus.ACTION_REQUIRED);

        task.retryManually(1L, NOW);
        assertThat(ProcessingStatus.of(task)).isEqualTo(ProcessingStatus.DELAYED);

        task.complete(LectureHpTaskOutcome.SUCCEEDED, NOW);
        assertThat(ProcessingStatus.of(task)).isNull();
    }
}

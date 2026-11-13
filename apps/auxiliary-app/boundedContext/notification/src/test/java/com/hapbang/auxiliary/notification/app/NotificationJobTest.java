package com.hapbang.auxiliary.notification.app;

import static org.assertj.core.api.Assertions.*;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/** 알림 작업의 락 번호(JobLockKey 규칙: 이슈 번호 × 1000 + 순번, 모듈 안에서 겹치지 않음은 모듈 테스트가 본다) */
class NotificationJobTest {

    @Test
    void 알림_작업의_락_번호와_태그는_겹치지_않고_그_작업을_더한_이슈_번호_x_1000_더하기_순번이다() {
        assertThat(Arrays.stream(NotificationJob.values()).map(NotificationJob::lockKey).distinct().count())
                .isEqualTo(NotificationJob.values().length);
        assertThat(Arrays.stream(NotificationJob.values()).map(NotificationJob::tag).distinct().count())
                .isEqualTo(NotificationJob.values().length);
        assertThat(NotificationJob.values()).allSatisfy(job -> {
            // 회원 복제본 Backfill #859, 예약 배치 #928
            assertThat(job.lockKey() / 1000).isIn(859L, 928L);
            assertThat(job.tag()).startsWith("notification-");
        });
    }
}

package com.hapbang.appearance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HpTaskTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 12, 0);
    private static final String KEY = "APPEARANCE_REQUEST-12-RELEASE-1";

    private static HpTask release() {
        return HpTask.release(HpTaskRefType.APPEARANCE_REQUEST, 12L, 1L, 30_000, HpTaskReason.REQUEST_REJECTED, KEY,
                NOW);
    }

    @Test
    @DisplayName("반환 작업은 PENDING, 시도 0회, 바로 실행할 수 있는 상태로 만든다")
    void create() {
        HpTask task = release();

        assertThat(task.getTaskType()).isEqualTo(HpTaskType.RELEASE);
        assertThat(task.getStatus()).isEqualTo(HpTaskStatus.PENDING);
        assertThat(task.getAttemptCount()).isZero();
        assertThat(task.getMemberId()).isEqualTo(1L);
        assertThat(task.getIdempotencyKey()).isEqualTo(KEY);
        assertThat(task.isRunnable(NOW)).isTrue();
    }

    @Test
    @DisplayName("반환 금액이 0 이하면 만들 수 없다")
    void positiveAmount() {
        assertThatThrownBy(() -> HpTask.release(HpTaskRefType.APPEARANCE_REQUEST, 12L, 1L, 0,
                HpTaskReason.REQUEST_REJECTED, KEY, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("성공하면 DONE, 시도 1회, 완료 시각 기록. 이후에는 실행 대상이 아니다")
    void done() {
        HpTask task = release();

        task.markDone(NOW);

        assertThat(task.getStatus()).isEqualTo(HpTaskStatus.DONE);
        assertThat(task.getAttemptCount()).isEqualTo(1);
        assertThat(task.getCompletedAt()).isEqualTo(NOW);
        assertThat(task.isRunnable(NOW)).isFalse();
    }

    @Test
    @DisplayName("실패하면 PENDING 그대로, 시도 횟수·오류·다음 시도 시각 기록. 다음 시도 시각 전에는 실행 대상이 아니다")
    void failed() {
        HpTask task = release();
        LocalDateTime retryAt = NOW.plusMinutes(1);

        task.markFailed("BillingUnavailableException: timeout", retryAt);

        assertThat(task.getStatus()).isEqualTo(HpTaskStatus.PENDING);
        assertThat(task.getAttemptCount()).isEqualTo(1);
        assertThat(task.getLastError()).isEqualTo("BillingUnavailableException: timeout");
        assertThat(task.isRunnable(NOW)).isFalse();
        assertThat(task.isRunnable(retryAt)).isTrue();
    }

    @Test
    @DisplayName("오류 메시지는 1,000자로 자른다 (DB 컬럼 길이)")
    void truncateError() {
        HpTask task = release();

        task.markFailed("가".repeat(1500), NOW);

        assertThat(task.getLastError()).hasSize(1000);
    }

    @Test
    @DisplayName("완료된 작업에 다시 결과를 기록할 수 없다 (코드 오류)")
    void onlyPending() {
        HpTask task = release();
        task.markDone(NOW);

        assertThatThrownBy(() -> task.markDone(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> task.markFailed("x", NOW)).isInstanceOf(IllegalStateException.class);
    }
}

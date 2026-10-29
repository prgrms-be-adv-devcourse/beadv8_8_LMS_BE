package com.hapbang.appearance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
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
    @DisplayName("Billing 요청 ID는 {대상}-{대상 ID}-{동작}-{순번}이고 Billing이 허용하는 문자(영문·숫자·'-'·'_')만 쓴다")
    void requestId() {
        String requestId = HpTask.requestId(HpTaskRefType.APPEARANCE_REQUEST, 12L, HpTaskType.RELEASE, 1);

        assertThat(requestId).isEqualTo(KEY);
        assertThat(requestId).matches("[A-Za-z0-9_-]{1,200}");
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

        task.markFailed("IllegalStateException: timeout", retryAt);

        assertThat(task.getStatus()).isEqualTo(HpTaskStatus.PENDING);
        assertThat(task.getAttemptCount()).isEqualTo(1);
        assertThat(task.getLastError()).isEqualTo("IllegalStateException: timeout");
        assertThat(task.isRunnable(NOW)).isFalse();
        assertThat(task.isRunnable(retryAt)).isTrue();
    }

    @Test
    @DisplayName("결과를 알 수 없는 실패는 재시도 정책대로 다음 시도 시각을 정하고(1분 → 2분), 최대 횟수에 닿으면 DEAD")
    void recordFailure() {
        HpTaskRetryPolicy policy = new HpTaskRetryPolicy(Duration.ofMinutes(1), Duration.ofHours(1), 3, 0.2);
        HpTask task = release();

        task.recordFailure("timeout", policy, NOW, 0);
        assertThat(task.getStatus()).isEqualTo(HpTaskStatus.PENDING);
        assertThat(task.getNextAttemptAt()).isEqualTo(NOW.plusMinutes(1));

        task.recordFailure("timeout", policy, NOW, 0);
        assertThat(task.getNextAttemptAt()).isEqualTo(NOW.plusMinutes(2));

        task.recordFailure("timeout", policy, NOW, 0);
        assertThat(task.getStatus()).isEqualTo(HpTaskStatus.DEAD);
        assertThat(task.getAttemptCount()).isEqualTo(3);
        assertThat(task.getCompletedAt()).isEqualTo(NOW);
        assertThat(task.isRunnable(NOW.plusDays(1))).isFalse();
    }

    @Test
    @DisplayName("Billing 거절은 바로 DEAD: 시도 1, 오류 기록, 더는 실행 대상이 아니다")
    void markDead() {
        HpTask task = release();

        task.markDead("HOLD_NOT_FOUND: HP 보류를 찾을 수 없습니다.", NOW);

        assertThat(task.getStatus()).isEqualTo(HpTaskStatus.DEAD);
        assertThat(task.getAttemptCount()).isEqualTo(1);
        assertThat(task.getLastError()).startsWith("HOLD_NOT_FOUND");
        assertThat(task.isPending()).isFalse();
        assertThatThrownBy(() -> task.markDone(NOW)).isInstanceOf(IllegalStateException.class);
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

    @Test
    @DisplayName("관리자 다시 시도(#397): DEAD → PENDING, 시도 0회, 바로 실행 가능, 완료 시각 비움, 멱등 키·마지막 오류 유지, 다시 시도 기록")
    void reopenForManualRetry() {
        HpTask task = release();
        task.markDead("HOLD_NOT_FOUND: HP 보류를 찾을 수 없습니다.", NOW);
        LocalDateTime later = NOW.plusHours(3);

        task.reopenForManualRetry(9L, later);

        assertThat(task.getStatus()).isEqualTo(HpTaskStatus.PENDING);
        assertThat(task.getAttemptCount()).isZero();
        assertThat(task.isRunnable(later)).isTrue();
        assertThat(task.getCompletedAt()).isNull();
        assertThat(task.getIdempotencyKey()).isEqualTo(KEY);
        assertThat(task.getLastError()).startsWith("HOLD_NOT_FOUND");
        assertThat(task.getManualRetryCount()).isEqualTo(1);
        assertThat(task.getLastManualRetryBy()).isEqualTo(9L);
        assertThat(task.getLastManualRetryAt()).isEqualTo(later);
    }

    @Test
    @DisplayName("다시 시도한 뒤 결과를 알 수 없는 실패는 재시도 규칙을 처음부터 적용한다 (바로 DEAD가 아니라 1분 뒤 재시도)")
    void retryBudgetResetsAfterManualRetry() {
        HpTaskRetryPolicy policy = new HpTaskRetryPolicy(Duration.ofMinutes(1), Duration.ofHours(1), 2, 0);
        HpTask task = release();
        task.recordFailure("timeout", policy, NOW, 0);
        task.recordFailure("timeout", policy, NOW, 0);
        assertThat(task.getStatus()).isEqualTo(HpTaskStatus.DEAD);

        task.reopenForManualRetry(9L, NOW);
        task.recordFailure("timeout", policy, NOW, 0);

        assertThat(task.getStatus()).isEqualTo(HpTaskStatus.PENDING);
        assertThat(task.getAttemptCount()).isEqualTo(1);
        assertThat(task.getNextAttemptAt()).isEqualTo(NOW.plusMinutes(1));

        task.recordFailure("timeout", policy, NOW, 0);
        assertThat(task.getStatus()).isEqualTo(HpTaskStatus.DEAD);
    }

    @Test
    @DisplayName("다시 시도할 때마다 다시 시도 횟수가 늘고 마지막 관리자·시각으로 바뀐다")
    void manualRetryCountAccumulates() {
        HpTask task = release();
        task.markDead("x", NOW);
        task.reopenForManualRetry(9L, NOW);
        task.markDead("x", NOW.plusMinutes(1));

        task.reopenForManualRetry(10L, NOW.plusMinutes(2));

        assertThat(task.getManualRetryCount()).isEqualTo(2);
        assertThat(task.getLastManualRetryBy()).isEqualTo(10L);
        assertThat(task.getLastManualRetryAt()).isEqualTo(NOW.plusMinutes(2));
    }

    @Test
    @DisplayName("DEAD가 아닌 작업(대기·완료)은 다시 시도할 수 없다 → HP_TASK_NOT_DEAD")
    void reopenOnlyDead() {
        HpTask pending = release();
        HpTask done = release();
        done.markDone(NOW);

        assertThatThrownBy(() -> pending.reopenForManualRetry(9L, NOW))
                .isInstanceOfSatisfying(AppearanceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AppearanceErrorCode.HP_TASK_NOT_DEAD));
        assertThatThrownBy(() -> done.reopenForManualRetry(9L, NOW))
                .isInstanceOfSatisfying(AppearanceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AppearanceErrorCode.HP_TASK_NOT_DEAD));
        assertThat(pending.getManualRetryCount()).isZero();
    }
}

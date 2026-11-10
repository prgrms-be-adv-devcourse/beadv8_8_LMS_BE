package com.hapbang.lecture.domain;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

/** 주문의 HP 작업으로 결제 단계를 판정한다 (D58 (3), D69 (2), D73 (3)) */
class OrderHoldTasksTest {

    private static final Long ORDER_ID = 3L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 12, 0);

    @Test
    void 모든_보류가_성공해야_결제가_끝난다() {
        LectureHpTask first = succeeded(hold(31L));
        LectureHpTask second = hold(32L);

        assertThat(OrderHoldTasks.of(List.of(first, second)).allHoldsSucceeded(List.of(31L, 32L))).isFalse();

        succeeded(second);
        assertThat(OrderHoldTasks.of(List.of(first, second)).allHoldsSucceeded(List.of(31L, 32L))).isTrue();
    }

    @Test
    void 보류가_필요_없는_항목은_성공으로_보고_필요한_항목이_없으면_바로_끝난다() {
        // 항목 32는 구매자 금액이 0이라 보류가 없다
        assertThat(OrderHoldTasks.of(List.of(succeeded(hold(31L)))).allHoldsSucceeded(List.of(31L))).isTrue();
        assertThat(OrderHoldTasks.of(List.of()).allHoldsSucceeded(List.of())).isTrue();
        assertThat(OrderHoldTasks.of(List.of()).allHoldsSucceeded(List.of(31L))).isFalse();
    }

    @Test
    void 성공한_보류_중_반환_작업이_없는_항목만_반환한다() {
        LectureHpTask compensated = succeeded(hold(31L));
        LectureHpTask needsCompensation = succeeded(hold(32L));
        LectureHpTask rejected = rejected(hold(33L));
        LectureHpTask unknown = hold(34L);

        OrderHoldTasks tasks = OrderHoldTasks.of(
                List.of(compensated, compensation(31L), needsCompensation, rejected, unknown));

        assertThat(tasks.holdsToCompensate()).containsExactly(needsCompensation);
    }

    @Test
    void 모든_보류가_끝나고_성공한_보류의_반환이_모두_끝나야_결제_실패다() {
        LectureHpTask held = succeeded(hold(31L));
        LectureHpTask rejected = rejected(hold(32L));
        LectureHpTask compensation = compensation(31L);

        assertThat(OrderHoldTasks.of(List.of(held, rejected, compensation)).isFailureSettled()).isFalse();

        succeeded(compensation);
        assertThat(OrderHoldTasks.of(List.of(held, rejected, compensation)).isFailureSettled()).isTrue();
    }

    @Test
    void 결과를_모르는_보류가_남으면_결제_실패가_아니다() {
        LectureHpTask unknown = hold(31L);
        unknown.scheduleRetry("UNKNOWN_RESULT", NOW.plusMinutes(1), NOW);

        assertThat(OrderHoldTasks.of(List.of(rejected(hold(32L)), unknown)).isFailureSettled()).isFalse();
    }

    @Test
    void 반환할_보류가_없으면_거절만으로_결제_실패다() {
        LectureHpTask skipped = hold(32L);
        skipped.complete(LectureHpTaskOutcome.SKIPPED, NOW);

        assertThat(OrderHoldTasks.of(List.of(rejected(hold(31L)), skipped)).isFailureSettled()).isTrue();
    }

    @Test
    void 보류_무효화로_끝낸_보류는_무효화가_성공해야_결제_실패다() {
        LectureHpTask voided = voided(hold(31L));
        LectureHpTask held = succeeded(hold(32L));
        LectureHpTask compensation = succeeded(compensation(32L));
        LectureHpTask holdVoid = holdVoid(31L);

        assertThat(OrderHoldTasks.of(List.of(voided, held, compensation)).isFailureSettled()).isFalse();
        assertThat(OrderHoldTasks.of(List.of(voided, held, compensation, holdVoid)).isFailureSettled()).isFalse();

        succeeded(holdVoid);
        assertThat(OrderHoldTasks.of(List.of(voided, held, compensation, holdVoid)).isFailureSettled()).isTrue();
        // 무효화로 끝낸 보류는 성공한 보류가 아니라 반환하지 않는다
        assertThat(OrderHoldTasks.of(List.of(voided, holdVoid)).holdsToCompensate()).isEmpty();
    }

    @Test
    void 무효화_작업도_처리_상태에_센다() {
        LectureHpTask voided = voided(hold(31L));
        LectureHpTask holdVoid = holdVoid(31L);

        assertThat(OrderHoldTasks.of(List.of(voided, holdVoid)).processingStatus())
                .isEqualTo(ProcessingStatus.PROCESSING);

        holdVoid.markDead("HOLD_OWNER_MISMATCH", NOW);
        assertThat(OrderHoldTasks.of(List.of(voided, holdVoid)).processingStatus())
                .isEqualTo(ProcessingStatus.ACTION_REQUIRED);

        succeeded(holdVoid);
        assertThat(OrderHoldTasks.of(List.of(voided, holdVoid)).processingStatus()).isNull();
    }

    @Test
    void 처리_상태는_끝나지_않은_작업_중_가장_나쁜_상태다() {
        LectureHpTask processing = hold(31L);
        LectureHpTask delayed = hold(32L);
        delayed.scheduleRetry("UNKNOWN_RESULT", NOW.plusMinutes(1), NOW);
        LectureHpTask dead = compensation(33L);
        dead.markDead("AMOUNT_MISMATCH", NOW);

        assertThat(OrderHoldTasks.of(List.of(processing)).processingStatus()).isEqualTo(ProcessingStatus.PROCESSING);
        assertThat(OrderHoldTasks.of(List.of(processing, delayed)).processingStatus())
                .isEqualTo(ProcessingStatus.DELAYED);
        assertThat(OrderHoldTasks.of(List.of(processing, delayed, dead)).processingStatus())
                .isEqualTo(ProcessingStatus.ACTION_REQUIRED);
        assertThat(OrderHoldTasks.of(List.of(succeeded(hold(34L)))).processingStatus()).isNull();
    }

    @Test
    void 결제와_관계없는_작업은_판정에서_뺀다() {
        LectureHpTask cancel = LectureHpTask.create(LectureHpTaskPurpose.CANCEL, "LECTURE-CANCEL-31", ORDER_ID, 31L,
                7L, null, 10_000L, NOW);
        cancel.markDead("AMOUNT_MISMATCH", NOW);

        OrderHoldTasks tasks = OrderHoldTasks.of(List.of(succeeded(hold(31L)), cancel));

        assertThat(tasks.allHoldsSucceeded(List.of(31L))).isTrue();
        assertThat(tasks.processingStatus()).isNull();
    }

    private static LectureHpTask hold(Long orderItemId) {
        return LectureHpTask.create(LectureHpTaskPurpose.ORDER_HOLD, "LECTURE-HOLD-" + orderItemId, ORDER_ID,
                orderItemId, 7L, null, 10_000L, NOW);
    }

    private static LectureHpTask compensation(Long orderItemId) {
        return LectureHpTask.create(LectureHpTaskPurpose.HOLD_COMPENSATION,
                "LECTURE-HOLD-COMPENSATE-" + orderItemId, ORDER_ID, orderItemId, 7L, null, 10_000L, NOW);
    }

    private static LectureHpTask holdVoid(Long orderItemId) {
        return LectureHpTask.create(LectureHpTaskPurpose.HOLD_VOID, "LECTURE-HOLD-VOID-" + orderItemId, ORDER_ID,
                orderItemId, 7L, null, 10_000L, NOW);
    }

    private static LectureHpTask voided(LectureHpTask hold) {
        hold.markDead("IDEMPOTENCY_KEY_CONFLICT", NOW);
        hold.voidByAdmin(1L, NOW);
        return hold;
    }

    private static LectureHpTask succeeded(LectureHpTask task) {
        task.complete(LectureHpTaskOutcome.SUCCEEDED, NOW);
        return task;
    }

    private static LectureHpTask rejected(LectureHpTask task) {
        task.complete(LectureHpTaskOutcome.REJECTED, NOW);
        return task;
    }
}

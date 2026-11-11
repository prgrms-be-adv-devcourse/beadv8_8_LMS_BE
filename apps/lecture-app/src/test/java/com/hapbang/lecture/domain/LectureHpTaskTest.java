package com.hapbang.lecture.domain;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

/** HP 작업의 관리자 처리 규칙. 실행·재시도 규칙은 LectureHpTaskExecutorTest가 맡는다 */
class LectureHpTaskTest {

    private static final Long ADMIN_ID = 1L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 12, 0);

    @Test
    void DEAD인_주문_항목_보류만_보류_무효화로_끝내고_관리자를_남긴다() {
        LectureHpTask hold = task(LectureHpTaskPurpose.ORDER_HOLD);
        hold.markDead("IDEMPOTENCY_KEY_CONFLICT", NOW);

        hold.voidByAdmin(ADMIN_ID, NOW.plusMinutes(1));

        assertThat(hold.getStatus()).isEqualTo(LectureHpTaskStatus.COMPLETED);
        assertThat(hold.getOutcome()).isEqualTo(LectureHpTaskOutcome.VOIDED);
        assertThat(hold.getVoidedByUserId()).isEqualTo(ADMIN_ID);
        assertThat(hold.getCompletedAt()).isEqualTo(NOW.plusMinutes(1));
        // 원인은 남긴다
        assertThat(hold.getLastErrorCode()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
    }

    @Test
    void DEAD가_아닌_보류는_보류_무효화할_수_없다() {
        LectureHpTask pending = task(LectureHpTaskPurpose.ORDER_HOLD);

        assertThatThrownBy(() -> pending.voidByAdmin(ADMIN_ID, NOW))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.HP_TASK_NOT_DEAD);
        assertThat(pending.getStatus()).isEqualTo(LectureHpTaskStatus.PENDING);
    }

    @Test
    void 주문_항목_보류가_아닌_작업은_보류_무효화할_수_없다() {
        for (LectureHpTaskPurpose purpose : LectureHpTaskPurpose.values()) {
            if (purpose == LectureHpTaskPurpose.ORDER_HOLD) {
                continue;
            }
            LectureHpTask dead = task(purpose);
            dead.markDead("AMOUNT_MISMATCH", NOW);

            assertThatThrownBy(() -> dead.voidByAdmin(ADMIN_ID, NOW))
                    .isInstanceOf(LectureException.class)
                    .extracting("errorCode").isEqualTo(LectureErrorCode.HP_TASK_NOT_VOIDABLE);
            assertThat(dead.getStatus()).isEqualTo(LectureHpTaskStatus.DEAD);
        }
    }

    @Test
    void 구매자_0원_앵커의_보류_반환_무효화는_등록하고_지급_0원과_음수는_거절한다() {
        for (LectureHpTaskPurpose purpose : LectureHpTaskPurpose.values()) {
            if (purpose.getTaskType() == LectureHpTaskType.PAYOUT) {
                assertThatThrownBy(() -> LectureHpTask.create(purpose, "zero", 3L, 31L, 7L, 9L, 0L, NOW))
                        .isInstanceOf(IllegalArgumentException.class);
            } else {
                assertThat(LectureHpTask.create(purpose, "zero", 3L, 31L, 7L, null, 0L, NOW).getAmountHp()).isZero();
            }
        }
        assertThatThrownBy(() -> LectureHpTask.create(LectureHpTaskPurpose.CANCEL, "negative", 3L, 31L, 7L,
                null, -1L, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    private static LectureHpTask task(LectureHpTaskPurpose purpose) {
        Long receiverUserId = purpose.getTaskType() == LectureHpTaskType.PAYOUT ? 9L : null;
        return LectureHpTask.create(purpose, purpose + "-31", 3L, 31L, 7L, receiverUserId, 10_000L, NOW);
    }
}

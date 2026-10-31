package com.hapbang.lecture.domain;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 주문 응답에 싣는 처리 상태 (D65 (2), D69 (2), D70 (7)) */
class OrderProcessingStatusesTest {

    private static final Long ORDER_ID = 3L;
    private static final LocalDateTime SEPT_30 = LocalDateTime.of(2026, 9, 30, 10, 0);
    private static final LocalDateTime PAYOUT_AT = LocalDateTime.of(2026, 10, 31, 0, 0);

    @Test
    void 결제_단계면_주문과_모든_항목에_주문_단위_값을_싣는다() {
        LectureOrder order = order(false, 1L, 2L);
        LectureHpTask delayed = task(LectureHpTaskPurpose.ORDER_HOLD, 1L);
        delayed.scheduleRetry("UNKNOWN_RESULT", SEPT_30.plusMinutes(1), SEPT_30);

        OrderProcessingStatuses statuses = OrderProcessingStatuses.of(order, List.of(delayed,
                task(LectureHpTaskPurpose.ORDER_HOLD, 2L)));

        assertThat(statuses.order()).isEqualTo(ProcessingStatus.DELAYED);
        assertThat(statuses.item(1L)).isEqualTo(ProcessingStatus.DELAYED);
        assertThat(statuses.item(2L)).isEqualTo(ProcessingStatus.DELAYED);
    }

    @Test
    void 반환_지급_처리_중인_항목은_자기_작업으로_정하고_주문_단위_값은_없다() {
        LectureOrder order = order(true, 1L, 2L, 3L);
        order.requestCancelItem(1L, SEPT_30);
        order.confirmItem(2L, SEPT_30);
        order.startPayoutItem(2L, PAYOUT_AT);
        LectureHpTask cancel = task(LectureHpTaskPurpose.CANCEL, 1L);
        LectureHpTask payout = task(LectureHpTaskPurpose.PAYOUT, 2L);
        payout.markDead("PAYOUT_ALREADY_SETTLED", PAYOUT_AT);
        // 같은 항목의 끝난 결제 작업은 지금 상태와 관계없다
        LectureHpTask oldHold = task(LectureHpTaskPurpose.ORDER_HOLD, 1L);
        oldHold.complete(LectureHpTaskOutcome.SUCCEEDED, SEPT_30);

        OrderProcessingStatuses statuses = OrderProcessingStatuses.of(order, List.of(cancel, payout, oldHold));

        assertThat(statuses.order()).isNull();
        assertThat(statuses.item(1L)).isEqualTo(ProcessingStatus.PROCESSING);
        assertThat(statuses.item(2L)).isEqualTo(ProcessingStatus.ACTION_REQUIRED);
        assertThat(statuses.item(3L)).isNull();
    }

    @Test
    void 작업이_아직_없는_처리_중_항목은_처리_상태가_없다() {
        LectureOrder order = order(true, 1L);
        order.requestCancelItem(1L, SEPT_30);

        assertThat(OrderProcessingStatuses.of(order, List.of()).item(1L)).isNull();
    }

    private static LectureOrder order(boolean paid, Long... itemIds) {
        List<Lecture> lectures = LongStream.rangeClosed(1, itemIds.length)
                .mapToObj(i -> withId(onSaleLecture(), i * 10))
                .toList();
        LectureOrder order = LectureOrder.place(OTHER_USER_ID, lectures, Set.of(), SEPT_30);
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        for (int i = 0; i < itemIds.length; i++) {
            ReflectionTestUtils.setField(order.getItems().get(i), "id", itemIds[i]);
        }
        if (paid) {
            order.markPaid(SEPT_30);
        }
        return order;
    }

    private static LectureHpTask task(LectureHpTaskPurpose purpose, Long orderItemId) {
        Long receiver = purpose == LectureHpTaskPurpose.PAYOUT ? SELLER_ID : null;
        return LectureHpTask.create(purpose, purpose + "-" + orderItemId, ORDER_ID, orderItemId, OTHER_USER_ID,
                receiver, PRICE_HP, SEPT_30);
    }
}

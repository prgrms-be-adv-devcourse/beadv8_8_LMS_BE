package com.hapbang.lecture.domain;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;


/**
 * 내용 다름 이의 제기 접수·판단의 상태 규칙과 기한 경계 (설계 4.6 불변식, 6.2).
 */
class LectureOrderObjectionTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final Long INQUIRY_ID = 500L;
    private static final String REASON = "강의 소개와 영상 내용이 다릅니다.";
    private static final LocalDateTime SEPT_30 = LocalDateTime.of(2026, 9, 30, 10, 0);
    /** 9/30 구매의 지급 예정 시각 */
    private static final LocalDateTime PAYOUT_AT = LocalDateTime.of(2026, 10, 31, 0, 0);
    /** 9/30 구매의 자동 구매 확정 시각 */
    private static final LocalDateTime AUTO_CONFIRM_AT = LocalDateTime.of(2026, 10, 8, 0, 0);
    private static final LocalDateTime OCT_5 = LocalDateTime.of(2026, 10, 5, 12, 0);
    private static final LocalDateTime OCT_6 = LocalDateTime.of(2026, 10, 6, 12, 0);

    @Test
    void 구매_항목에_이의_제기를_접수하면_FILED가_되고_문의ID와_사유와_시각을_남긴다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);

        OrderItem item = order.fileObjection(1L, BUYER_ID, INQUIRY_ID, REASON, OCT_5);

        assertThat(item.getObjectionStatus()).isEqualTo(ObjectionStatus.FILED);
        assertThat(item.getObjectionInquiryId()).isEqualTo(INQUIRY_ID);
        assertThat(item.getObjectionReason()).isEqualTo(REASON);
        assertThat(item.getObjectionFiledAt()).isEqualTo(OCT_5);
        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.PURCHASED);
    }

    @Test
    void 구매_확정한_항목에도_이의_제기할_수_있다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);
        order.confirmItem(1L, SEPT_30);

        OrderItem item = order.fileObjection(1L, BUYER_ID, INQUIRY_ID, REASON, OCT_5);

        assertThat(item.getObjectionStatus()).isEqualTo(ObjectionStatus.FILED);
        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.CONFIRMED);
    }

    @Test
    void 지급_예정_시각_직전까지_이의_제기할_수_있다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);

        OrderItem item = order.fileObjection(1L, BUYER_ID, INQUIRY_ID, REASON, PAYOUT_AT.minusNanos(1));

        assertThat(item.getObjectionStatus()).isEqualTo(ObjectionStatus.FILED);
    }

    @Test
    void 지급_예정_시각부터_이의_제기할_수_없다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);

        assertRefused(() -> order.fileObjection(1L, BUYER_ID, INQUIRY_ID, REASON, PAYOUT_AT),
                LectureErrorCode.OBJECTION_PERIOD_EXPIRED);
        assertThat(order.getItems().getFirst().getObjectionStatus()).isEqualTo(ObjectionStatus.NONE);
    }

    @Test
    void 구매자가_아니면_이의_제기할_수_없다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);

        assertRefused(() -> order.fileObjection(1L, 4L, INQUIRY_ID, REASON, OCT_5), LectureErrorCode.OBJECTION_NOT_BUYER);
    }

    @Test
    void 구매_취소되거나_반환된_항목은_이의_제기할_수_없다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L, 2L);
        cancel(order, 1L, SEPT_30);
        order.fileObjection(2L, BUYER_ID, INQUIRY_ID, REASON, OCT_5);
        order.resolveObjection(2L, INQUIRY_ID, true, ADMIN_ID, OCT_6);

        assertRefused(() -> order.fileObjection(1L, BUYER_ID, 501L, REASON, OCT_6), LectureErrorCode.OBJECTION_NOT_OBJECTIONABLE);
        assertRefused(() -> order.fileObjection(2L, BUYER_ID, 502L, REASON, OCT_6), LectureErrorCode.OBJECTION_NOT_OBJECTIONABLE);
    }

    @Test
    void 지급된_항목은_이의_제기할_수_없다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);
        // 지급(#150) 전이라 상태만 바꾼다
        ReflectionTestUtils.setField(order.getItems().getFirst(), "status", OrderItemStatus.PAID_OUT);

        assertRefused(() -> order.fileObjection(1L, BUYER_ID, INQUIRY_ID, REASON, OCT_5), LectureErrorCode.OBJECTION_ALREADY_PAID_OUT);
    }

    @Test
    void 이미_접수되었거나_기각된_항목은_다시_이의_제기할_수_없다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L, 2L);
        order.fileObjection(1L, BUYER_ID, INQUIRY_ID, REASON, OCT_5);
        order.fileObjection(2L, BUYER_ID, 501L, REASON, OCT_5);
        order.resolveObjection(2L, 501L, false, ADMIN_ID, OCT_6);

        assertRefused(() -> order.fileObjection(1L, BUYER_ID, 502L, REASON, OCT_6), LectureErrorCode.OBJECTION_ALREADY_FILED);
        assertRefused(() -> order.fileObjection(2L, BUYER_ID, 503L, REASON, OCT_6), LectureErrorCode.OBJECTION_ALREADY_FILED);
    }

    @Test
    void 여러_사유에_해당하면_상태를_기한보다_먼저_알린다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L, 2L);
        order.fileObjection(1L, BUYER_ID, INQUIRY_ID, REASON, OCT_5);
        order.resolveObjection(1L, INQUIRY_ID, false, ADMIN_ID, OCT_6);
        ReflectionTestUtils.setField(order.getItems().get(1), "status", OrderItemStatus.PAID_OUT);

        assertRefused(() -> order.fileObjection(1L, BUYER_ID, 501L, REASON, PAYOUT_AT), LectureErrorCode.OBJECTION_ALREADY_FILED);
        assertRefused(() -> order.fileObjection(2L, BUYER_ID, 502L, REASON, PAYOUT_AT), LectureErrorCode.OBJECTION_ALREADY_PAID_OUT);
        assertRefused(() -> order.fileObjection(2L, 4L, 503L, REASON, PAYOUT_AT), LectureErrorCode.OBJECTION_NOT_BUYER);
    }

    @Test
    void 사유가_비어_있으면_호출한_쪽_오류다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);

        assertThatThrownBy(() -> order.fileObjection(1L, BUYER_ID, INQUIRY_ID, " ", OCT_5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> order.fileObjection(1L, BUYER_ID, INQUIRY_ID, null, OCT_5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 이의_제기를_인정하면_항목이_반환되고_판단자와_시각을_남긴다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L, 2L);
        order.confirmItem(1L, SEPT_30);
        order.fileObjection(1L, BUYER_ID, INQUIRY_ID, REASON, OCT_5);

        OrderItem item = order.resolveObjection(1L, INQUIRY_ID, true, ADMIN_ID, OCT_6);

        // 반환이 끝나기 전에는 반환 처리 중이다 (D59 (1), D70 (2))
        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.RETURN_PENDING);
        assertThat(item.getReturnedAt()).isNull();
        order.completeReturnItem(1L, OCT_6.plusMinutes(1));
        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.RETURNED);
        assertThat(item.getReturnedAt()).isEqualTo(OCT_6.plusMinutes(1));
        assertThat(item.getObjectionStatus()).isEqualTo(ObjectionStatus.ACCEPTED);
        assertThat(item.getObjectionResolvedAt()).isEqualTo(OCT_6);
        assertThat(item.getObjectionResolvedByUserId()).isEqualTo(ADMIN_ID);
        assertThat(order.getStatus()).isEqualTo(LectureOrderStatus.PAID);
        assertThat(order.getCanceledAt()).isNull();
    }

    @Test
    void 마지막_남은_항목의_이의_제기를_인정하면_주문도_취소된다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L, 2L);
        cancel(order, 1L, SEPT_30);
        order.fileObjection(2L, BUYER_ID, INQUIRY_ID, REASON, OCT_5);

        order.resolveObjection(2L, INQUIRY_ID, true, ADMIN_ID, OCT_6);
        assertThat(order.getStatus()).isEqualTo(LectureOrderStatus.PAID);

        order.completeReturnItem(2L, OCT_6.plusMinutes(1));

        assertThat(order.getStatus()).isEqualTo(LectureOrderStatus.CANCELED);
        assertThat(order.getCanceledAt()).isEqualTo(OCT_6.plusMinutes(1));
    }

    @Test
    void 이의_제기를_기각하면_REJECTED가_되고_항목_상태는_그대로다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);
        order.confirmItem(1L, SEPT_30);
        order.fileObjection(1L, BUYER_ID, INQUIRY_ID, REASON, OCT_5);

        OrderItem item = order.resolveObjection(1L, INQUIRY_ID, false, ADMIN_ID, OCT_6);

        assertThat(item.getObjectionStatus()).isEqualTo(ObjectionStatus.REJECTED);
        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.CONFIRMED);
        assertThat(item.getReturnedAt()).isNull();
        assertThat(item.getObjectionResolvedAt()).isEqualTo(OCT_6);
        assertThat(item.getObjectionResolvedByUserId()).isEqualTo(ADMIN_ID);
        assertThat(order.getStatus()).isEqualTo(LectureOrderStatus.PAID);
    }

    @Test
    void 접수되지_않았거나_이미_판단한_이의_제기는_판단할_수_없다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L, 2L);
        order.fileObjection(2L, BUYER_ID, INQUIRY_ID, REASON, OCT_5);
        order.resolveObjection(2L, INQUIRY_ID, false, ADMIN_ID, OCT_6);

        assertRefused(() -> order.resolveObjection(1L, INQUIRY_ID, true, ADMIN_ID, OCT_6), LectureErrorCode.OBJECTION_NOT_FILED);
        assertRefused(() -> order.resolveObjection(2L, INQUIRY_ID, true, ADMIN_ID, OCT_6), LectureErrorCode.OBJECTION_NOT_FILED);
        assertThat(order.getItems().get(1).getStatus()).isEqualTo(OrderItemStatus.PURCHASED);
    }

    @Test
    void 접수한_문의와_다른_문의로는_판단할_수_없다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);
        order.fileObjection(1L, BUYER_ID, INQUIRY_ID, REASON, OCT_5);

        assertRefused(() -> order.resolveObjection(1L, 501L, true, ADMIN_ID, OCT_6), LectureErrorCode.OBJECTION_INQUIRY_MISMATCH);
        assertThat(order.getItems().getFirst().getObjectionStatus()).isEqualTo(ObjectionStatus.FILED);
    }

    @Test
    void 이의_제기가_접수된_항목은_구매_취소할_수_없다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);
        order.fileObjection(1L, BUYER_ID, INQUIRY_ID, REASON, OCT_5);

        assertThatThrownBy(() -> cancel(order, 1L, OCT_5))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.OBJECTION_FILED);
    }

    @Test
    void 접수한_이의_제기를_철회하면_NONE이_되고_문의ID와_사유와_접수_시각을_비운다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);
        order.confirmItem(1L, SEPT_30);
        order.fileObjection(1L, BUYER_ID, INQUIRY_ID, REASON, OCT_5);

        OrderItem item = order.withdrawObjection(1L, BUYER_ID, INQUIRY_ID);

        assertThat(item.getObjectionStatus()).isEqualTo(ObjectionStatus.NONE);
        assertThat(item.getObjectionInquiryId()).isNull();
        assertThat(item.getObjectionReason()).isNull();
        assertThat(item.getObjectionFiledAt()).isNull();
        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.CONFIRMED);
    }

    @Test
    void 구매자가_아니면_철회할_수_없다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);
        order.fileObjection(1L, BUYER_ID, INQUIRY_ID, REASON, OCT_5);

        assertRefused(() -> order.withdrawObjection(1L, 4L, INQUIRY_ID), LectureErrorCode.OBJECTION_NOT_BUYER);
        assertThat(order.getItems().getFirst().getObjectionStatus()).isEqualTo(ObjectionStatus.FILED);
    }

    @Test
    void 접수되지_않았거나_이미_판단한_이의_제기는_철회할_수_없다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L, 2L, 3L);
        order.fileObjection(2L, BUYER_ID, 501L, REASON, OCT_5);
        order.resolveObjection(2L, 501L, false, ADMIN_ID, OCT_6);
        order.fileObjection(3L, BUYER_ID, 502L, REASON, OCT_5);
        order.resolveObjection(3L, 502L, true, ADMIN_ID, OCT_6);

        assertRefused(() -> order.withdrawObjection(1L, BUYER_ID, INQUIRY_ID), LectureErrorCode.OBJECTION_NOT_FILED);
        assertRefused(() -> order.withdrawObjection(2L, BUYER_ID, 501L), LectureErrorCode.OBJECTION_NOT_FILED);
        assertRefused(() -> order.withdrawObjection(3L, BUYER_ID, 502L), LectureErrorCode.OBJECTION_NOT_FILED);
        assertThat(order.getItems()).extracting(OrderItem::getObjectionStatus)
                .containsExactly(ObjectionStatus.NONE, ObjectionStatus.REJECTED, ObjectionStatus.ACCEPTED);
    }

    @Test
    void 접수한_문의와_다른_문의로는_철회할_수_없다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);
        order.fileObjection(1L, BUYER_ID, INQUIRY_ID, REASON, OCT_5);

        assertRefused(() -> order.withdrawObjection(1L, BUYER_ID, 501L), LectureErrorCode.OBJECTION_INQUIRY_MISMATCH);
        assertThat(order.getItems().getFirst())
                .extracting(OrderItem::getObjectionStatus, OrderItem::getObjectionInquiryId)
                .containsExactly(ObjectionStatus.FILED, INQUIRY_ID);
    }

    @Test
    void 여러_사유에_해당하면_철회는_구매자를_먼저_확인한다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L, 2L);
        order.fileObjection(2L, BUYER_ID, INQUIRY_ID, REASON, OCT_5);

        assertRefused(() -> order.withdrawObjection(1L, 4L, 501L), LectureErrorCode.OBJECTION_NOT_BUYER);
        assertRefused(() -> order.withdrawObjection(2L, 4L, 501L), LectureErrorCode.OBJECTION_NOT_BUYER);
    }

    @Test
    void 철회한_항목은_자동_구매_확정_시각_전이면_구매_취소할_수_있고_기한은_늘지_않는다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L, 2L);
        order.fileObjection(1L, BUYER_ID, INQUIRY_ID, REASON, OCT_5);
        order.fileObjection(2L, BUYER_ID, 501L, REASON, OCT_5);
        order.withdrawObjection(1L, BUYER_ID, INQUIRY_ID);
        order.withdrawObjection(2L, BUYER_ID, 501L);

        OrderItem canceled = cancel(order, 1L, OCT_6);

        assertThat(canceled.getStatus()).isEqualTo(OrderItemStatus.CANCELED);
        assertThatThrownBy(() -> cancel(order, 2L, AUTO_CONFIRM_AT))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.CANCEL_PERIOD_EXPIRED);
    }

    @Test
    void 철회한_항목에_다른_문의로_다시_이의_제기할_수_있다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L, 2L);
        order.fileObjection(1L, BUYER_ID, INQUIRY_ID, REASON, OCT_5);
        order.fileObjection(2L, BUYER_ID, 501L, REASON, OCT_5);
        order.withdrawObjection(1L, BUYER_ID, INQUIRY_ID);
        order.withdrawObjection(2L, BUYER_ID, 501L);

        OrderItem refiled = order.fileObjection(1L, BUYER_ID, 502L, "다시 확인해 보니 내용이 다릅니다.", OCT_6);

        assertThat(refiled).extracting(OrderItem::getObjectionStatus, OrderItem::getObjectionInquiryId)
                .containsExactly(ObjectionStatus.FILED, 502L);
        assertRefused(() -> order.fileObjection(2L, BUYER_ID, 503L, REASON, PAYOUT_AT), LectureErrorCode.OBJECTION_PERIOD_EXPIRED);
    }

    @Test
    void 철회한_구매_확정_항목은_지급_예정_시각부터_지급_대상이다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);
        order.confirmItem(1L, SEPT_30);
        order.fileObjection(1L, BUYER_ID, INQUIRY_ID, REASON, OCT_5);
        OrderItem item = order.getItems().getFirst();
        assertThat(item.isPayable(PAYOUT_AT)).isFalse();

        order.withdrawObjection(1L, BUYER_ID, INQUIRY_ID);

        assertThat(item.isPayable(PAYOUT_AT.minusNanos(1))).isFalse();
        assertThat(item.isPayable(PAYOUT_AT)).isTrue();
    }

    @Test
    void 결제가_끝나지_않은_항목은_이의_제기할_수_없다() {
        LectureOrder pending = LectureOrder.place(BUYER_ID, List.of(withId(onSaleLecture(), 10L)), Set.of(), SEPT_30);
        ReflectionTestUtils.setField(pending.getItems().getFirst(), "id", 1L);
        LectureOrder failed = LectureOrder.place(BUYER_ID, List.of(withId(onSaleLecture(), 20L)), Set.of(), SEPT_30);
        ReflectionTestUtils.setField(failed.getItems().getFirst(), "id", 2L);
        failed.markHoldFailed();

        assertRefused(() -> pending.fileObjection(1L, BUYER_ID, INQUIRY_ID, REASON, OCT_5), LectureErrorCode.OBJECTION_NOT_OBJECTIONABLE);
        assertRefused(() -> failed.fileObjection(2L, BUYER_ID, INQUIRY_ID, REASON, OCT_5), LectureErrorCode.OBJECTION_NOT_OBJECTIONABLE);
    }

    @Test
    void 처리_중인_항목의_이의_제기는_상태에_따라_거부된다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L, 2L, 3L, 4L);
        order.requestCancelItem(1L, SEPT_30);
        order.fileObjection(2L, BUYER_ID, 501L, REASON, OCT_5);
        order.resolveObjection(2L, 501L, true, ADMIN_ID, OCT_6);
        order.confirmItem(3L, SEPT_30);
        order.startPayoutItem(3L, PAYOUT_AT);
        order.confirmItem(4L, SEPT_30);
        order.fileObjection(4L, BUYER_ID, 502L, REASON, OCT_5);
        order.resolveObjection(4L, 502L, false, ADMIN_ID, OCT_6);
        order.startPayoutItem(4L, PAYOUT_AT);

        // 취소·반환 처리 중은 NOT_OBJECTIONABLE, 지급 처리 중은 PERIOD_EXPIRED(이미 판단된 건은 ALREADY_FILED) (D59 (6))
        assertRefused(() -> order.fileObjection(1L, BUYER_ID, 503L, REASON, OCT_6), LectureErrorCode.OBJECTION_NOT_OBJECTIONABLE);
        assertRefused(() -> order.fileObjection(2L, BUYER_ID, 504L, REASON, OCT_6), LectureErrorCode.OBJECTION_NOT_OBJECTIONABLE);
        assertRefused(() -> order.fileObjection(3L, BUYER_ID, 505L, REASON, OCT_6), LectureErrorCode.OBJECTION_PERIOD_EXPIRED);
        assertRefused(() -> order.fileObjection(4L, BUYER_ID, 506L, REASON, OCT_6), LectureErrorCode.OBJECTION_ALREADY_FILED);
    }

    /** 구매 취소를 요청하고 반환까지 끝낸다 */
    private static OrderItem cancel(LectureOrder order, Long orderItemId, LocalDateTime now) {
        order.requestCancelItem(orderItemId, now);
        return order.completeCancelItem(orderItemId, now);
    }

    private static LectureOrder placeWithItemIds(LocalDateTime now, Long... itemIds) {
        List<Lecture> lectures = LongStream.rangeClosed(1, itemIds.length)
                .mapToObj(i -> withId(onSaleLecture(), i * 10))
                .toList();
        LectureOrder order = LectureOrder.place(BUYER_ID, lectures, Set.of(), now);
        for (int i = 0; i < itemIds.length; i++) {
            ReflectionTestUtils.setField(order.getItems().get(i), "id", itemIds[i]);
        }
        order.markPaid(now);
        return order;
    }

    private static void assertRefused(Runnable action, LectureErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }
}

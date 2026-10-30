package com.hapbang.lecture.domain;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.shared.lecture.exception.LectureObjectionRefusedException;
import com.hapbang.shared.lecture.exception.LectureObjectionRefusedException.Reason;

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
                Reason.PERIOD_EXPIRED);
        assertThat(order.getItems().getFirst().getObjectionStatus()).isEqualTo(ObjectionStatus.NONE);
    }

    @Test
    void 구매자가_아니면_이의_제기할_수_없다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);

        assertRefused(() -> order.fileObjection(1L, 4L, INQUIRY_ID, REASON, OCT_5), Reason.NOT_BUYER);
    }

    @Test
    void 구매_취소되거나_반환된_항목은_이의_제기할_수_없다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L, 2L);
        order.cancelItem(1L, SEPT_30);
        order.fileObjection(2L, BUYER_ID, INQUIRY_ID, REASON, OCT_5);
        order.resolveObjection(2L, INQUIRY_ID, true, ADMIN_ID, OCT_6);

        assertRefused(() -> order.fileObjection(1L, BUYER_ID, 501L, REASON, OCT_6), Reason.NOT_OBJECTIONABLE);
        assertRefused(() -> order.fileObjection(2L, BUYER_ID, 502L, REASON, OCT_6), Reason.NOT_OBJECTIONABLE);
    }

    @Test
    void 지급된_항목은_이의_제기할_수_없다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);
        // 지급(#150) 전이라 상태만 바꾼다
        ReflectionTestUtils.setField(order.getItems().getFirst(), "status", OrderItemStatus.PAID_OUT);

        assertRefused(() -> order.fileObjection(1L, BUYER_ID, INQUIRY_ID, REASON, OCT_5), Reason.ALREADY_PAID_OUT);
    }

    @Test
    void 이미_접수되었거나_기각된_항목은_다시_이의_제기할_수_없다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L, 2L);
        order.fileObjection(1L, BUYER_ID, INQUIRY_ID, REASON, OCT_5);
        order.fileObjection(2L, BUYER_ID, 501L, REASON, OCT_5);
        order.resolveObjection(2L, 501L, false, ADMIN_ID, OCT_6);

        assertRefused(() -> order.fileObjection(1L, BUYER_ID, 502L, REASON, OCT_6), Reason.ALREADY_FILED);
        assertRefused(() -> order.fileObjection(2L, BUYER_ID, 503L, REASON, OCT_6), Reason.ALREADY_FILED);
    }

    @Test
    void 여러_사유에_해당하면_상태를_기한보다_먼저_알린다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L, 2L);
        order.fileObjection(1L, BUYER_ID, INQUIRY_ID, REASON, OCT_5);
        order.resolveObjection(1L, INQUIRY_ID, false, ADMIN_ID, OCT_6);
        ReflectionTestUtils.setField(order.getItems().get(1), "status", OrderItemStatus.PAID_OUT);

        assertRefused(() -> order.fileObjection(1L, BUYER_ID, 501L, REASON, PAYOUT_AT), Reason.ALREADY_FILED);
        assertRefused(() -> order.fileObjection(2L, BUYER_ID, 502L, REASON, PAYOUT_AT), Reason.ALREADY_PAID_OUT);
        assertRefused(() -> order.fileObjection(2L, 4L, 503L, REASON, PAYOUT_AT), Reason.NOT_BUYER);
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

        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.RETURNED);
        assertThat(item.getReturnedAt()).isEqualTo(OCT_6);
        assertThat(item.getObjectionStatus()).isEqualTo(ObjectionStatus.ACCEPTED);
        assertThat(item.getObjectionResolvedAt()).isEqualTo(OCT_6);
        assertThat(item.getObjectionResolvedByUserId()).isEqualTo(ADMIN_ID);
        assertThat(order.getStatus()).isEqualTo(LectureOrderStatus.PAID);
        assertThat(order.getCanceledAt()).isNull();
    }

    @Test
    void 마지막_남은_항목의_이의_제기를_인정하면_주문도_취소된다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L, 2L);
        order.cancelItem(1L, SEPT_30);
        order.fileObjection(2L, BUYER_ID, INQUIRY_ID, REASON, OCT_5);

        order.resolveObjection(2L, INQUIRY_ID, true, ADMIN_ID, OCT_6);

        assertThat(order.getStatus()).isEqualTo(LectureOrderStatus.CANCELED);
        assertThat(order.getCanceledAt()).isEqualTo(OCT_6);
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

        assertRefused(() -> order.resolveObjection(1L, INQUIRY_ID, true, ADMIN_ID, OCT_6), Reason.NOT_FILED);
        assertRefused(() -> order.resolveObjection(2L, INQUIRY_ID, true, ADMIN_ID, OCT_6), Reason.NOT_FILED);
        assertThat(order.getItems().get(1).getStatus()).isEqualTo(OrderItemStatus.PURCHASED);
    }

    @Test
    void 접수한_문의와_다른_문의로는_판단할_수_없다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);
        order.fileObjection(1L, BUYER_ID, INQUIRY_ID, REASON, OCT_5);

        assertRefused(() -> order.resolveObjection(1L, 501L, true, ADMIN_ID, OCT_6), Reason.INQUIRY_MISMATCH);
        assertThat(order.getItems().getFirst().getObjectionStatus()).isEqualTo(ObjectionStatus.FILED);
    }

    @Test
    void 이의_제기가_접수된_항목은_구매_취소할_수_없다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);
        order.fileObjection(1L, BUYER_ID, INQUIRY_ID, REASON, OCT_5);

        assertThatThrownBy(() -> order.cancelItem(1L, OCT_5))
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

        assertRefused(() -> order.withdrawObjection(1L, 4L, INQUIRY_ID), Reason.NOT_BUYER);
        assertThat(order.getItems().getFirst().getObjectionStatus()).isEqualTo(ObjectionStatus.FILED);
    }

    @Test
    void 접수되지_않았거나_이미_판단한_이의_제기는_철회할_수_없다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L, 2L, 3L);
        order.fileObjection(2L, BUYER_ID, 501L, REASON, OCT_5);
        order.resolveObjection(2L, 501L, false, ADMIN_ID, OCT_6);
        order.fileObjection(3L, BUYER_ID, 502L, REASON, OCT_5);
        order.resolveObjection(3L, 502L, true, ADMIN_ID, OCT_6);

        assertRefused(() -> order.withdrawObjection(1L, BUYER_ID, INQUIRY_ID), Reason.NOT_FILED);
        assertRefused(() -> order.withdrawObjection(2L, BUYER_ID, 501L), Reason.NOT_FILED);
        assertRefused(() -> order.withdrawObjection(3L, BUYER_ID, 502L), Reason.NOT_FILED);
        assertThat(order.getItems()).extracting(OrderItem::getObjectionStatus)
                .containsExactly(ObjectionStatus.NONE, ObjectionStatus.REJECTED, ObjectionStatus.ACCEPTED);
    }

    @Test
    void 접수한_문의와_다른_문의로는_철회할_수_없다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);
        order.fileObjection(1L, BUYER_ID, INQUIRY_ID, REASON, OCT_5);

        assertRefused(() -> order.withdrawObjection(1L, BUYER_ID, 501L), Reason.INQUIRY_MISMATCH);
        assertThat(order.getItems().getFirst())
                .extracting(OrderItem::getObjectionStatus, OrderItem::getObjectionInquiryId)
                .containsExactly(ObjectionStatus.FILED, INQUIRY_ID);
    }

    @Test
    void 여러_사유에_해당하면_철회는_구매자를_먼저_확인한다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L, 2L);
        order.fileObjection(2L, BUYER_ID, INQUIRY_ID, REASON, OCT_5);

        assertRefused(() -> order.withdrawObjection(1L, 4L, 501L), Reason.NOT_BUYER);
        assertRefused(() -> order.withdrawObjection(2L, 4L, 501L), Reason.NOT_BUYER);
    }

    @Test
    void 철회한_항목은_자동_구매_확정_시각_전이면_구매_취소할_수_있고_기한은_늘지_않는다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L, 2L);
        order.fileObjection(1L, BUYER_ID, INQUIRY_ID, REASON, OCT_5);
        order.fileObjection(2L, BUYER_ID, 501L, REASON, OCT_5);
        order.withdrawObjection(1L, BUYER_ID, INQUIRY_ID);
        order.withdrawObjection(2L, BUYER_ID, 501L);

        OrderItem canceled = order.cancelItem(1L, OCT_6);

        assertThat(canceled.getStatus()).isEqualTo(OrderItemStatus.CANCELED);
        assertThatThrownBy(() -> order.cancelItem(2L, AUTO_CONFIRM_AT))
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
        assertRefused(() -> order.fileObjection(2L, BUYER_ID, 503L, REASON, PAYOUT_AT), Reason.PERIOD_EXPIRED);
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

        assertRefused(() -> pending.fileObjection(1L, BUYER_ID, INQUIRY_ID, REASON, OCT_5), Reason.NOT_OBJECTIONABLE);
        assertRefused(() -> failed.fileObjection(2L, BUYER_ID, INQUIRY_ID, REASON, OCT_5), Reason.NOT_OBJECTIONABLE);
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

    private static void assertRefused(Runnable action, Reason reason) {
        assertThatThrownBy(action::run)
                .isInstanceOf(LectureObjectionRefusedException.class)
                .extracting("reason").isEqualTo(reason);
    }
}

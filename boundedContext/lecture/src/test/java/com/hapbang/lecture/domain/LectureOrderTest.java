package com.hapbang.lecture.domain;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class LectureOrderTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final LocalDateTime SEPT_30 = LocalDateTime.of(2026, 9, 30, 10, 0);

    @Test
    void 구매일_다음날부터_7일이_지나면_자동_구매_확정_시각이다() {
        OrderItem item = placeOne(SEPT_30);

        assertThat(item.getPurchasedAt()).isEqualTo(SEPT_30);
        assertThat(item.getAutoConfirmAt()).isEqualTo(LocalDateTime.of(2026, 10, 8, 0, 0));
    }

    @Test
    void 구매일_다음날부터_30일이_지나면_지급_예정_시각이다() {
        OrderItem item = placeOne(SEPT_30);

        assertThat(item.getPayoutScheduledAt()).isEqualTo(LocalDateTime.of(2026, 10, 31, 0, 0));
    }

    @Test
    void 자정_직전에_사도_그날을_구매일로_본다() {
        OrderItem item = placeOne(LocalDateTime.of(2026, 9, 30, 23, 59, 59));

        assertThat(item.getAutoConfirmAt()).isEqualTo(LocalDateTime.of(2026, 10, 8, 0, 0));
        assertThat(item.getPayoutScheduledAt()).isEqualTo(LocalDateTime.of(2026, 10, 31, 0, 0));
    }

    @Test
    void 주문하면_항목마다_주문_시점_제목과_가격을_남기고_합계를_계산한다() {
        Lecture first = withId(onSaleLecture(), 10L);
        Lecture second = withId(onSaleLecture(), 20L);
        second.updateInfo(SELLER_ID, "마이크 고르기", DESCRIPTION, 5_000L, THUMBNAIL_FILE_ID, NOW);

        LectureOrder order = LectureOrder.place(BUYER_ID, List.of(first, second), Set.of(), SEPT_30);

        assertThat(order.getStatus()).isEqualTo(LectureOrderStatus.PAID);
        assertThat(order.getOrderedAt()).isEqualTo(SEPT_30);
        assertThat(order.getTotalAmountHp()).isEqualTo(PRICE_HP + 5_000L);
        assertThat(order.getItems())
                .extracting(OrderItem::getLectureId, OrderItem::getSellerUserId, OrderItem::getBuyerUserId,
                        OrderItem::getLectureTitleSnapshot, OrderItem::getPriceHp, OrderItem::getStatus,
                        OrderItem::getObjectionStatus)
                .containsExactly(
                        tuple(10L, SELLER_ID, BUYER_ID, TITLE, PRICE_HP, OrderItemStatus.PURCHASED, ObjectionStatus.NONE),
                        tuple(20L, SELLER_ID, BUYER_ID, "마이크 고르기", 5_000L, OrderItemStatus.PURCHASED,
                                ObjectionStatus.NONE));
    }

    @Test
    void 한_주문에_같은_강의를_두_번_넣을_수_없다() {
        Lecture lecture = withId(onSaleLecture(), 10L);

        assertErrorCode(() -> LectureOrder.place(BUYER_ID, List.of(lecture, lecture), Set.of(), SEPT_30),
                LectureErrorCode.INVALID_REQUEST);
    }

    @Test
    void 강의가_없으면_주문할_수_없다() {
        assertErrorCode(() -> LectureOrder.place(BUYER_ID, List.of(), Set.of(), SEPT_30),
                LectureErrorCode.INVALID_REQUEST);
    }

    @Test
    void 본인_강의는_주문할_수_없다() {
        Lecture own = withId(onSaleLecture(), 10L);

        assertErrorCode(() -> LectureOrder.place(SELLER_ID, List.of(own), Set.of(), SEPT_30),
                LectureErrorCode.OWN_LECTURE_NOT_PURCHASABLE);
    }

    @Test
    void 판매_중이_아닌_강의는_주문할_수_없다() {
        Lecture pending = withId(pendingLecture(), 10L);

        assertErrorCode(() -> LectureOrder.place(BUYER_ID, List.of(pending), Set.of(), SEPT_30),
                LectureErrorCode.LECTURE_NOT_FOUND);
    }

    @Test
    void 이미_보유한_강의는_주문할_수_없다() {
        Lecture owned = withId(onSaleLecture(), 10L);

        assertErrorCode(() -> LectureOrder.place(BUYER_ID, List.of(owned), Set.of(10L), SEPT_30),
                LectureErrorCode.ALREADY_OWNED_LECTURE);
    }

    @Test
    void 여러_강의에_문제가_있으면_본인_강의_판매_중_보유_순서로_검사한다() {
        Lecture owned = withId(onSaleLecture(), 10L);
        Lecture pending = withId(pendingLecture(), 20L);
        Lecture onSale = withId(onSaleLecture(), 30L);
        List<Lecture> lectures = List.of(owned, pending, onSale);

        assertErrorCode(() -> LectureOrder.place(BUYER_ID, lectures, Set.of(10L), SEPT_30),
                LectureErrorCode.LECTURE_NOT_FOUND);
        assertErrorCode(() -> LectureOrder.place(SELLER_ID, lectures, Set.of(10L), SEPT_30),
                LectureErrorCode.OWN_LECTURE_NOT_PURCHASABLE);
    }

    @Test
    void 구매를_확정하면_직접_확정으로_기록된다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);
        LocalDateTime now = LocalDateTime.of(2026, 10, 3, 9, 0);

        OrderItem item = order.confirmItem(1L, now);

        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.CONFIRMED);
        assertThat(item.getConfirmationType()).isEqualTo(ConfirmationType.DIRECT);
        assertThat(item.getConfirmedAt()).isEqualTo(now);
    }

    @Test
    void 자동_확정_시각이_지난_뒤_확정하면_자동_확정으로_기록된다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);

        OrderItem item = order.confirmItem(1L, LocalDateTime.of(2026, 10, 9, 9, 0));

        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.CONFIRMED);
        assertThat(item.getConfirmationType()).isEqualTo(ConfirmationType.AUTO);
        assertThat(item.getConfirmedAt()).isEqualTo(LocalDateTime.of(2026, 10, 8, 0, 0));
    }

    @Test
    void 구매_상태가_아니면_확정할_수_없다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L, 2L);
        order.confirmItem(1L, SEPT_30);
        order.cancelItem(2L, SEPT_30);

        assertErrorCode(() -> order.confirmItem(1L, SEPT_30), LectureErrorCode.ORDER_ITEM_NOT_PURCHASED);
        assertErrorCode(() -> order.confirmItem(2L, SEPT_30), LectureErrorCode.ORDER_ITEM_NOT_PURCHASED);
    }

    @Test
    void 주문에_없는_항목은_확정_취소할_수_없다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);

        assertErrorCode(() -> order.confirmItem(99L, SEPT_30), LectureErrorCode.ORDER_ITEM_NOT_FOUND);
        assertErrorCode(() -> order.cancelItem(99L, SEPT_30), LectureErrorCode.ORDER_ITEM_NOT_FOUND);
        assertErrorCode(() -> order.consentToPlay(99L, SEPT_30), LectureErrorCode.ORDER_ITEM_NOT_FOUND);
    }

    @Test
    void 재생_동의하면_첫_재생으로_구매_확정된다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);
        LocalDateTime now = LocalDateTime.of(2026, 10, 3, 9, 0);

        OrderItem item = order.consentToPlay(1L, now);

        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.CONFIRMED);
        assertThat(item.getConfirmationType()).isEqualTo(ConfirmationType.FIRST_PLAY);
        assertThat(item.getConfirmedAt()).isEqualTo(now);
    }

    @Test
    void 자동_확정_시각이_지난_뒤_재생_동의해도_첫_재생으로_기록된다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);
        LocalDateTime now = LocalDateTime.of(2026, 10, 9, 9, 0);

        OrderItem item = order.consentToPlay(1L, now);

        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.CONFIRMED);
        assertThat(item.getConfirmationType()).isEqualTo(ConfirmationType.FIRST_PLAY);
        assertThat(item.getConfirmedAt()).isEqualTo(now);
    }

    @Test
    void 이미_확정한_항목에_재생_동의하면_그대로_둔다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);
        order.confirmItem(1L, SEPT_30);

        OrderItem item = order.consentToPlay(1L, SEPT_30.plusDays(1));

        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.CONFIRMED);
        assertThat(item.getConfirmationType()).isEqualTo(ConfirmationType.DIRECT);
        assertThat(item.getConfirmedAt()).isEqualTo(SEPT_30);
    }

    @Test
    void 지급된_항목에_재생_동의하면_그대로_둔다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);
        order.confirmItem(1L, SEPT_30);
        // 지급 메서드가 아직 없어(#149) 상태만 넣는다
        ReflectionTestUtils.setField(order.getItems().getFirst(), "status", OrderItemStatus.PAID_OUT);

        OrderItem item = order.consentToPlay(1L, SEPT_30.plusDays(31));

        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.PAID_OUT);
        assertThat(item.getConfirmationType()).isEqualTo(ConfirmationType.DIRECT);
    }

    @Test
    void 취소한_항목은_재생_동의할_수_없다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L, 2L);
        order.cancelItem(1L, SEPT_30);

        assertErrorCode(() -> order.consentToPlay(1L, SEPT_30), LectureErrorCode.ORDER_ITEM_NOT_PURCHASED);
    }

    @Test
    void 재생_동의한_항목은_구매_취소할_수_없다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);
        order.consentToPlay(1L, SEPT_30);

        assertErrorCode(() -> order.cancelItem(1L, SEPT_30), LectureErrorCode.ORDER_ITEM_NOT_PURCHASED);
    }

    @Test
    void 자동_확정_시각_직전까지_구매_취소할_수_있다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);
        LocalDateTime lastMoment = LocalDateTime.of(2026, 10, 7, 23, 59, 59);

        OrderItem item = order.cancelItem(1L, lastMoment);

        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.CANCELED);
        assertThat(item.getCanceledAt()).isEqualTo(lastMoment);
    }

    @Test
    void 자동_확정_시각부터_구매_취소할_수_없다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);

        assertErrorCode(() -> order.cancelItem(1L, LocalDateTime.of(2026, 10, 8, 0, 0)),
                LectureErrorCode.CANCEL_PERIOD_EXPIRED);
    }

    @Test
    void 구매_확정한_항목은_취소할_수_없다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);
        order.confirmItem(1L, SEPT_30);

        assertErrorCode(() -> order.cancelItem(1L, SEPT_30), LectureErrorCode.ORDER_ITEM_NOT_PURCHASED);
    }

    @Test
    void 일부_항목만_취소하면_주문은_결제_상태로_남는다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L, 2L);

        order.cancelItem(1L, SEPT_30);

        assertThat(order.getStatus()).isEqualTo(LectureOrderStatus.PAID);
        assertThat(order.getCanceledAt()).isNull();
        assertThat(order.progress()).isEqualTo(OrderProgress.IN_PROGRESS);
    }

    @Test
    void 마지막_남은_항목을_취소하면_주문도_취소된다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L, 2L);
        LocalDateTime later = SEPT_30.plusHours(1);

        order.cancelItem(1L, SEPT_30);
        order.cancelItem(2L, later);

        assertThat(order.getStatus()).isEqualTo(LectureOrderStatus.CANCELED);
        assertThat(order.getCanceledAt()).isEqualTo(later);
        assertThat(order.progress()).isNull();
    }

    private static LectureOrder placeWithItemIds(LocalDateTime now, Long... itemIds) {
        List<Lecture> lectures = LongStream.rangeClosed(1, itemIds.length)
                .mapToObj(i -> withId(onSaleLecture(), i * 10))
                .toList();
        LectureOrder order = LectureOrder.place(BUYER_ID, lectures, Set.of(), now);
        for (int i = 0; i < itemIds.length; i++) {
            ReflectionTestUtils.setField(order.getItems().get(i), "id", itemIds[i]);
        }
        return order;
    }

    private static void assertErrorCode(Runnable action, LectureErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }

    private static OrderItem placeOne(LocalDateTime now) {
        LectureOrder order = LectureOrder.place(BUYER_ID, List.of(withId(onSaleLecture(), 10L)), Set.of(), now);
        return order.getItems().getFirst();
    }
}

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
    /** 9월 30일 구매 항목의 지급 예정 시각 */
    private static final LocalDateTime PAYOUT_AT = LocalDateTime.of(2026, 10, 31, 0, 0);

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

        assertThat(order.getStatus()).isEqualTo(LectureOrderStatus.HOLD_PENDING);
        assertThat(order.getOrderedAt()).isEqualTo(SEPT_30);
        assertThat(order.getTotalAmountHp()).isEqualTo(PRICE_HP + 5_000L);
        assertThat(order.getItems())
                .extracting(OrderItem::getLectureId, OrderItem::getSellerUserId, OrderItem::getBuyerUserId,
                        OrderItem::getLectureTitleSnapshot, OrderItem::getPriceHp, OrderItem::getStatus,
                        OrderItem::getObjectionStatus)
                .containsExactly(
                        tuple(10L, SELLER_ID, BUYER_ID, TITLE, PRICE_HP, OrderItemStatus.HOLD_PENDING,
                                ObjectionStatus.NONE),
                        tuple(20L, SELLER_ID, BUYER_ID, "마이크 고르기", 5_000L, OrderItemStatus.HOLD_PENDING,
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
    void 자동_확정_시각이_지난_뒤_재생_동의하면_자동_확정으로_기록된다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);

        OrderItem item = order.consentToPlay(1L, LocalDateTime.of(2026, 10, 9, 9, 0));

        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.CONFIRMED);
        assertThat(item.getConfirmationType()).isEqualTo(ConfirmationType.AUTO);
        assertThat(item.getConfirmedAt()).isEqualTo(LocalDateTime.of(2026, 10, 8, 0, 0));
    }

    @Test
    void 자동_확정_시각_직전에_재생_동의하면_첫_재생으로_기록된다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);
        LocalDateTime lastMoment = LocalDateTime.of(2026, 10, 7, 23, 59, 59);

        OrderItem item = order.consentToPlay(1L, lastMoment);

        assertThat(item.getConfirmationType()).isEqualTo(ConfirmationType.FIRST_PLAY);
        assertThat(item.getConfirmedAt()).isEqualTo(lastMoment);
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
    void 자동_구매_확정_시각이_지나면_AUTO로_확정하고_확정_시각은_자동_구매_확정_시각이다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);

        OrderItem item = order.autoConfirmItem(1L, LocalDateTime.of(2026, 10, 8, 3, 0));

        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.CONFIRMED);
        assertThat(item.getConfirmationType()).isEqualTo(ConfirmationType.AUTO);
        assertThat(item.getConfirmedAt()).isEqualTo(LocalDateTime.of(2026, 10, 8, 0, 0));
    }

    @Test
    void 자동_구매_확정_시각과_같으면_자동_확정한다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);

        OrderItem item = order.autoConfirmItem(1L, LocalDateTime.of(2026, 10, 8, 0, 0));

        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.CONFIRMED);
        assertThat(item.getConfirmationType()).isEqualTo(ConfirmationType.AUTO);
    }

    @Test
    void 자동_구매_확정_시각_전이면_자동_확정해도_그대로다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);

        OrderItem item = order.autoConfirmItem(1L, LocalDateTime.of(2026, 10, 8, 0, 0).minusNanos(1));

        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.PURCHASED);
        assertThat(item.getConfirmationType()).isNull();
        assertThat(item.getConfirmedAt()).isNull();
    }

    @Test
    void 이미_구매_확정된_항목은_자동_확정해도_그대로다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);
        order.confirmItem(1L, SEPT_30);

        OrderItem item = order.autoConfirmItem(1L, LocalDateTime.of(2026, 10, 9, 0, 0));

        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.CONFIRMED);
        assertThat(item.getConfirmationType()).isEqualTo(ConfirmationType.DIRECT);
        assertThat(item.getConfirmedAt()).isEqualTo(SEPT_30);
    }

    @Test
    void 구매_취소된_항목은_자동_확정해도_그대로다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);
        order.cancelItem(1L, SEPT_30);

        OrderItem item = order.autoConfirmItem(1L, LocalDateTime.of(2026, 10, 9, 0, 0));

        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.CANCELED);
        assertThat(item.getConfirmationType()).isNull();
    }

    @Test
    void 주문에_없는_항목은_자동_확정할_수_없다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);

        assertErrorCode(() -> order.autoConfirmItem(99L, LocalDateTime.of(2026, 10, 9, 0, 0)),
                LectureErrorCode.ORDER_ITEM_NOT_FOUND);
    }

    @Test
    void 지급_시각이_지난_구매_확정_항목은_지급하고_지급_시각을_기록한다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);
        order.confirmItem(1L, SEPT_30);
        LocalDateTime now = LocalDateTime.of(2026, 10, 31, 1, 0);

        assertThat(order.payOutItem(1L, now)).hasValueSatisfying(item -> {
            assertThat(item.getId()).isEqualTo(1L);
            assertThat(item.getStatus()).isEqualTo(OrderItemStatus.PAID_OUT);
            assertThat(item.getPaidOutAt()).isEqualTo(now);
        });
        assertThat(order.getStatus()).isEqualTo(LectureOrderStatus.PAID);
        assertThat(order.progress()).isEqualTo(OrderProgress.COMPLETED);
    }

    @Test
    void 지급_시각과_같으면_지급한다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);
        order.confirmItem(1L, SEPT_30);

        assertThat(order.payOutItem(1L, PAYOUT_AT)).isPresent();
        assertThat(order.getItems().get(0).getStatus()).isEqualTo(OrderItemStatus.PAID_OUT);
    }

    @Test
    void 지급_시각_전이면_지급하지_않는다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);
        order.confirmItem(1L, SEPT_30);

        assertThat(order.payOutItem(1L, PAYOUT_AT.minusNanos(1))).isEmpty();
        assertThat(order.getItems().get(0).getStatus()).isEqualTo(OrderItemStatus.CONFIRMED);
        assertThat(order.getItems().get(0).getPaidOutAt()).isNull();
    }

    @Test
    void 이의_제기가_접수되거나_인정된_항목은_지급하지_않는다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L, 2L);
        order.confirmItem(1L, SEPT_30);
        order.confirmItem(2L, SEPT_30);
        // 이의 제기 접수·판단(#156)이 아직 없어 상태만 넣는다
        ReflectionTestUtils.setField(order.getItems().get(0), "objectionStatus", ObjectionStatus.FILED);
        ReflectionTestUtils.setField(order.getItems().get(1), "objectionStatus", ObjectionStatus.ACCEPTED);

        assertThat(order.payOutItem(1L, PAYOUT_AT)).isEmpty();
        assertThat(order.payOutItem(2L, PAYOUT_AT)).isEmpty();
        assertThat(order.getItems()).extracting(OrderItem::getStatus).containsOnly(OrderItemStatus.CONFIRMED);
    }

    @Test
    void 이의_제기가_기각된_항목은_지급한다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);
        order.confirmItem(1L, SEPT_30);
        ReflectionTestUtils.setField(order.getItems().get(0), "objectionStatus", ObjectionStatus.REJECTED);

        assertThat(order.payOutItem(1L, PAYOUT_AT)).isPresent();
        assertThat(order.getItems().get(0).getStatus()).isEqualTo(OrderItemStatus.PAID_OUT);
    }

    @Test
    void 구매_확정_전이거나_취소되거나_이미_지급된_항목은_지급하지_않는다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L, 2L, 3L);
        order.cancelItem(2L, SEPT_30);
        order.confirmItem(3L, SEPT_30);
        LocalDateTime firstPayout = PAYOUT_AT.plusHours(1);
        order.payOutItem(3L, firstPayout);

        assertThat(order.payOutItem(1L, PAYOUT_AT.plusDays(1))).isEmpty();
        assertThat(order.payOutItem(2L, PAYOUT_AT.plusDays(1))).isEmpty();
        assertThat(order.payOutItem(3L, PAYOUT_AT.plusDays(1))).isEmpty();
        assertThat(order.getItems())
                .extracting(OrderItem::getStatus, OrderItem::getPaidOutAt)
                .containsExactly(
                        tuple(OrderItemStatus.PURCHASED, null),
                        tuple(OrderItemStatus.CANCELED, null),
                        tuple(OrderItemStatus.PAID_OUT, firstPayout));
    }

    @Test
    void 주문에_없는_항목은_지급할_수_없다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);

        assertErrorCode(() -> order.payOutItem(99L, PAYOUT_AT), LectureErrorCode.ORDER_ITEM_NOT_FOUND);
    }

    @Test
    void 지급_실패를_기록하면_횟수와_마지막_코드_시각이_남는다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);
        order.confirmItem(1L, SEPT_30);
        LocalDateTime secondFailure = PAYOUT_AT.plusDays(1);

        order.recordPayoutFailure(1L, "WALLET_NOT_FOUND", false, PAYOUT_AT);
        assertThat(order.recordPayoutFailure(1L, "ObjectOptimisticLockingFailureException", false, secondFailure))
                .isPresent();

        assertThat(order.getItems().get(0))
                .extracting(OrderItem::getPayoutFailureCount, OrderItem::getLastPayoutFailureCode,
                        OrderItem::getLastPayoutFailedAt, OrderItem::getPayoutQuarantinedAt, OrderItem::getStatus)
                .containsExactly(2, "ObjectOptimisticLockingFailureException", secondFailure, null,
                        OrderItemStatus.CONFIRMED);
    }

    @Test
    void 영구_실패는_처음_실패할_때_격리한다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);
        order.confirmItem(1L, SEPT_30);
        order.recordPayoutFailure(1L, "WALLET_NOT_FOUND", false, PAYOUT_AT);

        order.recordPayoutFailure(1L, "PAYOUT_ALREADY_SETTLED", true, PAYOUT_AT.plusDays(1));

        OrderItem item = order.getItems().get(0);
        assertThat(item.getPayoutQuarantinedAt()).isEqualTo(PAYOUT_AT.plusDays(1));
        assertThat(item.isPayoutQuarantined()).isTrue();
        assertThat(item.getPayoutFailureCount()).isEqualTo(2);
        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.CONFIRMED);
    }

    @Test
    void 격리된_항목은_지급_대상이_아니다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);
        order.confirmItem(1L, SEPT_30);
        order.recordPayoutFailure(1L, "PAYOUT_ALREADY_SETTLED", true, PAYOUT_AT);

        assertThat(order.getItems().get(0).isPayable(PAYOUT_AT.plusDays(1))).isFalse();
        assertThat(order.payOutItem(1L, PAYOUT_AT.plusDays(1))).isEmpty();
        assertThat(order.getItems().get(0).getStatus()).isEqualTo(OrderItemStatus.CONFIRMED);
    }

    @Test
    void 지급_대상이_아니게_된_항목에는_지급_실패를_기록하지_않는다() {
        // 늦은 쪽의 낙관적 락 충돌로 실패한 경우: 다른 실행이 먼저 지급했거나 이의 제기 접수가 먼저 커밋되었다
        LectureOrder order = placeWithItemIds(SEPT_30, 1L, 2L, 3L, 4L);
        order.confirmItem(2L, SEPT_30);
        order.payOutItem(2L, PAYOUT_AT);
        order.confirmItem(3L, SEPT_30);
        ReflectionTestUtils.setField(order.getItems().get(2), "objectionStatus", ObjectionStatus.FILED);
        order.confirmItem(4L, SEPT_30);
        order.recordPayoutFailure(4L, "PAYOUT_ALREADY_SETTLED", true, PAYOUT_AT);

        assertThat(order.recordPayoutFailure(1L, "WALLET_NOT_FOUND", true, PAYOUT_AT)).isEmpty();
        assertThat(order.recordPayoutFailure(2L, "WALLET_NOT_FOUND", true, PAYOUT_AT)).isEmpty();
        assertThat(order.recordPayoutFailure(3L, "WALLET_NOT_FOUND", true, PAYOUT_AT)).isEmpty();
        // 이미 격리된 항목은 지급 배치가 처리하지 않으므로 기록도 바뀌지 않는다
        assertThat(order.recordPayoutFailure(4L, "HOLD_ALREADY_CLOSED", true, PAYOUT_AT.plusDays(1))).isEmpty();
        assertThat(order.getItems())
                .extracting(OrderItem::getPayoutFailureCount, OrderItem::getLastPayoutFailureCode,
                        OrderItem::getPayoutQuarantinedAt)
                .containsExactly(tuple(0, null, null), tuple(0, null, null), tuple(0, null, null),
                        tuple(1, "PAYOUT_ALREADY_SETTLED", PAYOUT_AT));
    }

    @Test
    void 격리를_해제하면_격리_시각만_비우고_실패_기록은_남긴다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);
        order.confirmItem(1L, SEPT_30);
        order.recordPayoutFailure(1L, "PAYOUT_ALREADY_SETTLED", true, PAYOUT_AT);

        order.releasePayoutQuarantine(1L);

        OrderItem item = order.getItems().get(0);
        assertThat(item)
                .extracting(OrderItem::getPayoutFailureCount, OrderItem::getLastPayoutFailureCode,
                        OrderItem::getLastPayoutFailedAt, OrderItem::getPayoutQuarantinedAt)
                .containsExactly(1, "PAYOUT_ALREADY_SETTLED", PAYOUT_AT, null);
        assertThat(item.isPayable(PAYOUT_AT.plusDays(1))).isTrue();
    }

    @Test
    void 격리되지_않은_항목은_격리를_해제할_수_없다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L);
        order.confirmItem(1L, SEPT_30);
        order.recordPayoutFailure(1L, "WALLET_NOT_FOUND", false, PAYOUT_AT);

        assertErrorCode(() -> order.releasePayoutQuarantine(1L), LectureErrorCode.PAYOUT_NOT_QUARANTINED);
        assertErrorCode(() -> order.releasePayoutQuarantine(99L), LectureErrorCode.ORDER_ITEM_NOT_FOUND);
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

    @Test
    void 주문하면_결제가_끝나기_전에는_구매_시각과_기한이_없다() {
        LectureOrder order = placePendingWithItemIds(SEPT_30, 1L, 2L);

        assertThat(order.getPaidAt()).isNull();
        assertThat(order.progress()).isNull();
        assertThat(order.getItems())
                .extracting(OrderItem::getPurchasedAt, OrderItem::getAutoConfirmAt, OrderItem::getPayoutScheduledAt)
                .containsOnly(tuple(null, null, null));
    }

    @Test
    void 모든_보류가_성공하면_구매_완료로_바꾸고_그_시각으로_구매_시각과_기한을_정한다() {
        LectureOrder order = placePendingWithItemIds(LocalDateTime.of(2026, 10, 7, 23, 59, 59), 1L, 2L);
        LocalDateTime paidAt = LocalDateTime.of(2026, 10, 8, 0, 0, 1);

        order.markPaid(paidAt);

        assertThat(order.getStatus()).isEqualTo(LectureOrderStatus.PAID);
        assertThat(order.getPaidAt()).isEqualTo(paidAt);
        assertThat(order.getOrderedAt()).isEqualTo(LocalDateTime.of(2026, 10, 7, 23, 59, 59));
        assertThat(order.getItems())
                .extracting(OrderItem::getStatus, OrderItem::getPurchasedAt, OrderItem::getAutoConfirmAt,
                        OrderItem::getPayoutScheduledAt)
                .containsOnly(tuple(OrderItemStatus.PURCHASED, paidAt, LocalDateTime.of(2026, 10, 16, 0, 0),
                        LocalDateTime.of(2026, 11, 8, 0, 0)));
    }

    @Test
    void 보류가_거절되면_결제_취소_처리_중이_되고_반환이_끝나면_결제_실패다() {
        LectureOrder order = placePendingWithItemIds(SEPT_30, 1L, 2L);

        order.startHoldCompensation();
        assertThat(order.getStatus()).isEqualTo(LectureOrderStatus.HOLD_COMPENSATING);
        assertThat(order.getItems()).extracting(OrderItem::getStatus)
                .containsOnly(OrderItemStatus.HOLD_COMPENSATING);

        order.markHoldFailed();
        assertThat(order.getStatus()).isEqualTo(LectureOrderStatus.HOLD_FAILED);
        assertThat(order.getItems()).extracting(OrderItem::getStatus).containsOnly(OrderItemStatus.HOLD_FAILED);
        assertThat(order.progress()).isNull();
    }

    @Test
    void 반환할_보류가_없으면_결제_처리_중에서_바로_결제_실패다() {
        LectureOrder order = placePendingWithItemIds(SEPT_30, 1L);

        order.markHoldFailed();

        assertThat(order.getStatus()).isEqualTo(LectureOrderStatus.HOLD_FAILED);
        assertThat(order.getItems()).extracting(OrderItem::getStatus).containsOnly(OrderItemStatus.HOLD_FAILED);
    }

    @Test
    void 결제_단계가_아닌_주문은_결제_상태를_바꿀_수_없다() {
        LectureOrder paid = placeWithItemIds(SEPT_30, 1L);
        LectureOrder compensating = placePendingWithItemIds(SEPT_30, 2L);
        compensating.startHoldCompensation();

        assertThatIllegalStateException().isThrownBy(() -> paid.markPaid(SEPT_30));
        assertThatIllegalStateException().isThrownBy(paid::startHoldCompensation);
        assertThatIllegalStateException().isThrownBy(paid::markHoldFailed);
        assertThatIllegalStateException().isThrownBy(() -> compensating.markPaid(SEPT_30));
    }

    @Test
    void 결제_처리_중인_항목은_구매_확정_재생_동의_구매_취소할_수_없다() {
        LectureOrder order = placePendingWithItemIds(SEPT_30, 1L);

        assertErrorCode(() -> order.confirmItem(1L, SEPT_30), LectureErrorCode.ORDER_ITEM_NOT_PURCHASED);
        assertErrorCode(() -> order.consentToPlay(1L, SEPT_30), LectureErrorCode.ORDER_ITEM_NOT_PURCHASED);
        assertErrorCode(() -> order.cancelItem(1L, SEPT_30), LectureErrorCode.ORDER_ITEM_NOT_PURCHASED);
    }

    @Test
    void 결제_처리_중인_항목은_보유지만_시청_가능하지_않다() {
        OrderItem pending = placePendingWithItemIds(SEPT_30, 1L).getItems().getFirst();
        LectureOrder failedOrder = placePendingWithItemIds(SEPT_30, 2L);
        failedOrder.markHoldFailed();
        OrderItem failed = failedOrder.getItems().getFirst();
        OrderItem purchased = placeOne(SEPT_30);

        assertThat(pending.isHeld()).isTrue();
        assertThat(pending.isViewable()).isFalse();
        assertThat(failed.isHeld()).isFalse();
        assertThat(failed.isViewable()).isFalse();
        assertThat(purchased.isHeld()).isTrue();
        assertThat(purchased.isViewable()).isTrue();
    }

    @Test
    void 이의_제기가_접수된_항목은_구매_취소할_수_없다() {
        LectureOrder order = placeWithItemIds(SEPT_30, 1L, 2L);
        // 이의 제기 접수·판단(#156)이 아직 없어 상태만 넣는다
        ReflectionTestUtils.setField(order.getItems().get(0), "objectionStatus", ObjectionStatus.FILED);
        ReflectionTestUtils.setField(order.getItems().get(1), "objectionStatus", ObjectionStatus.REJECTED);

        assertErrorCode(() -> order.cancelItem(1L, SEPT_30), LectureErrorCode.OBJECTION_FILED);
        assertErrorCode(() -> order.cancelItem(2L, SEPT_30), LectureErrorCode.OBJECTION_FILED);
        assertThat(order.getItems()).extracting(OrderItem::getStatus).containsOnly(OrderItemStatus.PURCHASED);
    }

    /** 모든 보류가 성공해 {@code now}에 구매 완료된 주문 */
    private static LectureOrder placeWithItemIds(LocalDateTime now, Long... itemIds) {
        LectureOrder order = placePendingWithItemIds(now, itemIds);
        order.markPaid(now);
        return order;
    }

    /** 결제 처리 중인 주문 */
    private static LectureOrder placePendingWithItemIds(LocalDateTime now, Long... itemIds) {
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
        order.markPaid(now);
        return order.getItems().getFirst();
    }
}

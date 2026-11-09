package com.hapbang.lecture.app;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.app.dto.OrderItemResponse;
import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.OrderItemStatus;
import com.hapbang.lecture.fake.FakeBillingApiClient;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.shared.lecture.event.LectureObjectionReturnCompletedEvent;
import com.hapbang.shared.lecture.event.LecturePurchaseChangedEvent;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 할인이 있는 주문 항목의 구매 취소·이의 제기 반환·지급 금액과, 금액이 0인 항목의 지갑 없는 즉시 완료를 Fake 지갑 Client와 DB로 확인한다.
 * 할인 스냅샷은 쿠폰 흐름 없이 주문 도메인으로 바로 남긴다(쿠폰 흐름은 OrderCouponReserveTest가 맡는다).
 */
@IntegrationTest
@WithMockUser(roles = "USER")
@RecordApplicationEvents
class OrderDiscountSettlementTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final long DISCOUNT = 3_000L;
    private static final Long INQUIRY_ID = 77L;

    @Autowired
    private LectureOrderFacade lectureOrderFacade;

    @Autowired
    private OrderObjectionResolveUseCase orderObjectionResolveUseCase;

    @Autowired
    private OrderPayoutUseCase orderPayoutUseCase;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private FakeBillingApiClient billing;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationEvents events;

    @Autowired
    @Qualifier(LectureConfig.CLOCK)
    private Clock clock;

    @BeforeEach
    void setUp() {
        billing.reset();
        billing.setSettledHp(PRICE_HP - DISCOUNT);
    }

    @Test
    void 판매자_할인_항목의_구매_취소_반환_금액은_구매자_금액이다() {
        LectureOrder order = savePaid(DISCOUNT, now());

        OrderItemResponse canceled = lectureOrderFacade.cancel(BUYER_ID, order.getId(), itemId(order));

        assertThat(canceled.status()).isEqualTo(OrderItemStatus.CANCELED);
        assertThat(taskAmount("CANCEL", itemId(order))).isEqualTo(PRICE_HP - DISCOUNT);
    }

    @Test
    void 구매자_금액이_0인_항목의_구매_취소는_지갑_없이_바로_구매_취소로_끝나고_구매_이벤트를_낸다() {
        LectureOrder order = savePaid(PRICE_HP, now());

        OrderItemResponse canceled = lectureOrderFacade.cancel(BUYER_ID, order.getId(), itemId(order));

        assertThat(canceled.status()).isEqualTo(OrderItemStatus.CANCELED);
        assertThat(billing.calls()).isEmpty();
        assertThat(taskCount(itemId(order))).isZero();
        assertThat(orderStatus(order.getId())).isEqualTo("CANCELED");
        assertThat(events.stream(LecturePurchaseChangedEvent.class)
                .filter(event -> event.purchase().orderItemId().equals(itemId(order)))).hasSize(2);
    }

    @Test
    void 이의_제기_인정_반환은_구매자_금액이고_0이면_지갑_없이_바로_반환으로_끝나며_고객센터에_알린다() {
        LectureOrder discounted = savePaid(DISCOUNT, now());
        LectureOrder free = savePaid(PRICE_HP, now());

        resolveAccepted(discounted);
        resolveAccepted(free);

        assertThat(taskAmount("RETURN", itemId(discounted))).isEqualTo(PRICE_HP - DISCOUNT);
        assertThat(taskCount(itemId(free))).isZero();
        assertThat(itemStatus(itemId(free))).isEqualTo("RETURNED");
        assertThat(events.stream(LectureObjectionReturnCompletedEvent.class))
                .containsExactly(new LectureObjectionReturnCompletedEvent(INQUIRY_ID, itemId(free)));
    }

    @Test
    void 지급_금액은_판매자_지급액이고_0이면_지갑_없이_바로_지급으로_끝난다() {
        LocalDateTime overdue = now().minusDays(32);
        LectureOrder discounted = saveConfirmed(DISCOUNT, overdue);
        LectureOrder free = saveConfirmed(PRICE_HP, overdue);

        orderPayoutUseCase.payOutDueItems();

        assertThat(taskAmount("PAYOUT", itemId(discounted))).isEqualTo(PRICE_HP - DISCOUNT);
        assertThat(taskCount(itemId(free))).isZero();
        assertThat(itemStatus(itemId(free))).isEqualTo("PAID_OUT");
        assertThat(billing.calls()).isEmpty();
    }

    /** 판매자 쿠폰으로 {@code sellerDiscount}만큼 할인해 {@code purchasedAt}에 산 주문 */
    private LectureOrder savePaid(long sellerDiscount, LocalDateTime purchasedAt) {
        Lecture lecture = lectureRepository.save(onSaleLecture());
        LectureOrder order = LectureOrder.place(BUYER_ID, List.of(lecture), Set.of(), purchasedAt);
        order.applyItemDiscount(lecture.getId(), sellerDiscount, 0L, sellerDiscount);
        order.markPaid(purchasedAt);
        return lectureOrderRepository.save(order);
    }

    private LectureOrder saveConfirmed(long sellerDiscount, LocalDateTime purchasedAt) {
        LectureOrder order = savePaid(sellerDiscount, purchasedAt);
        transactionTemplate.executeWithoutResult(status -> lectureOrderRepository.findById(order.getId()).orElseThrow()
                .confirmItem(itemId(order), purchasedAt));
        return order;
    }

    private void resolveAccepted(LectureOrder order) {
        transactionTemplate.executeWithoutResult(status -> lectureOrderRepository.findById(order.getId()).orElseThrow()
                .fileObjection(itemId(order), BUYER_ID, INQUIRY_ID, "내용이 다릅니다", now()));
        transactionTemplate.executeWithoutResult(status ->
                orderObjectionResolveUseCase.resolveObjection(itemId(order), INQUIRY_ID, true, ADMIN_ID));
    }

    private long taskAmount(String purpose, Long orderItemId) {
        return jdbcTemplate.queryForObject("select amount_hp from lectures.lecture_hp_task where purpose = ? "
                + "and order_item_id = ?", Long.class, purpose, orderItemId);
    }

    private long taskCount(Long orderItemId) {
        return jdbcTemplate.queryForObject("select count(*) from lectures.lecture_hp_task where order_item_id = ?",
                Long.class, orderItemId);
    }

    private String itemStatus(Long orderItemId) {
        return jdbcTemplate.queryForObject("select status from lectures.order_item where id = ?", String.class,
                orderItemId);
    }

    private String orderStatus(Long orderId) {
        return jdbcTemplate.queryForObject("select status from lectures.lecture_order where id = ?", String.class,
                orderId);
    }

    private static Long itemId(LectureOrder order) {
        return order.getItems().getFirst().getId();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}

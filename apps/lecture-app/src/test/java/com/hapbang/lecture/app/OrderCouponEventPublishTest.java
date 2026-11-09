package com.hapbang.lecture.app;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.app.dto.OrderCouponSelection;
import com.hapbang.lecture.app.dto.OrderResponse;
import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.LectureOrderStatus;
import com.hapbang.lecture.domain.OrderItemCoupon;
import com.hapbang.lecture.fake.CommittedCouponOrderEvents;
import com.hapbang.lecture.fake.FakeBillingApiClient;
import com.hapbang.lecture.fake.FakeCouponClient;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.lecture.out.OrderItemCouponRepository;
import com.hapbang.lecture.out.coupon.CouponCallResult;
import com.hapbang.shared.coupon.api.CouponCalculateRequest;
import com.hapbang.shared.coupon.api.CouponCalculateResponse;
import com.hapbang.shared.coupon.api.CouponReserveRequest;
import com.hapbang.shared.coupon.api.CouponReserveResponse;
import com.hapbang.shared.lecture.event.OrderItemCancelCompletedEvent;
import com.hapbang.shared.lecture.event.OrderItemPaidEvent;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 쿠폰을 쓴 주문 항목의 결제 완료·취소 완료 이벤트가 결제 완료·반환 완료를 기록하는 트랜잭션과 함께 커밋되는지 확인한다.
 * 커밋된 이벤트만 모으는 {@link CommittedCouponOrderEvents}로 본다. Kafka 토픽·key·헤더는 LectureSyncEventKafkaTest가 맡는다.
 */
@IntegrationTest
@WithMockUser(roles = "USER")
class OrderCouponEventPublishTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final long DISCOUNT = 3_000L;
    private static final long MEMBER_COUPON_ID = 501L;
    private static final long RESERVED_GENERATION = 5L;
    private static final Long INQUIRY_ID = 77L;

    @Autowired
    private LectureOrderFacade lectureOrderFacade;

    @Autowired
    private OrderCancelUseCase orderCancelUseCase;

    @Autowired
    private OrderObjectionResolveUseCase orderObjectionResolveUseCase;

    @Autowired
    private LectureHpTaskExecutor lectureHpTaskExecutor;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private OrderItemCouponRepository orderItemCouponRepository;

    @Autowired
    private FakeBillingApiClient billing;

    @Autowired
    private FakeCouponClient coupon;

    @Autowired
    private CommittedCouponOrderEvents events;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier(LectureConfig.CLOCK)
    private Clock clock;

    @BeforeEach
    void setUp() {
        billing.reset();
        billing.setSettledHp(PRICE_HP - DISCOUNT);
        coupon.reset();
        events.reset();
    }

    @Test
    void 쿠폰_주문의_결제가_끝나면_결제_완료를_기록하는_트랜잭션에서_항목마다_결제_완료_이벤트를_낸다() {
        Lecture lecture = saveOnSale();
        coupon.respond(request -> new CouponCallResult.Succeeded<>(calculated((CouponCalculateRequest) request)));
        coupon.respond(request -> new CouponCallResult.Succeeded<>(reserved((CouponReserveRequest) request)));

        OrderResponse response = lectureOrderFacade.createOrder(BUYER_ID, List.of(lecture.getId()),
                List.of(new OrderCouponSelection(MEMBER_COUPON_ID, lecture.getId())), PRICE_HP - DISCOUNT);

        assertThat(response.status()).isEqualTo(LectureOrderStatus.PAID);
        Long orderItemId = response.items().getFirst().orderItemId();
        assertThat(events.paid()).singleElement().satisfies(event -> {
            assertThat(event.eventId()).isEqualTo("order-item-paid:" + orderItemId);
            assertThat(event.orderId()).isEqualTo(response.orderId());
            assertThat(event.orderItemId()).isEqualTo(orderItemId);
            assertThat(event.memberId()).isEqualTo(BUYER_ID);
            assertThat(event.usages()).containsExactly(
                    new OrderItemPaidEvent.Usage(MEMBER_COUPON_ID, RESERVED_GENERATION));
            // 결제 완료 시각(KST)과 같은 시각이다. DB는 마이크로초까지 남긴다
            assertThat(event.paidAt().truncatedTo(ChronoUnit.MICROS))
                    .isEqualTo(response.paidAt().atZone(LectureConfig.ZONE).toInstant());
        });
        assertThat(events.cancelCompleted()).isEmpty();
    }

    @Test
    void 쿠폰_없는_주문은_결제와_구매_취소에서_쿠폰_이벤트를_내지_않는다() {
        billing.setSettledHp(PRICE_HP);
        Lecture lecture = saveOnSale();

        OrderResponse response = lectureOrderFacade.createOrder(BUYER_ID, List.of(lecture.getId()));
        lectureOrderFacade.cancel(BUYER_ID, response.orderId(), response.items().getFirst().orderItemId());

        assertThat(events.paid()).isEmpty();
        assertThat(events.cancelCompleted()).isEmpty();
    }

    @Test
    void 구매_취소_반환이_끝나면_반환_작업의_요청_ID를_실은_취소_완료_이벤트를_낸다() {
        LectureOrder order = savePaidWithCoupon(DISCOUNT);
        Long orderItemId = itemId(order);

        lectureOrderFacade.cancel(BUYER_ID, order.getId(), orderItemId);

        assertThat(events.cancelCompleted()).singleElement().satisfies(event -> {
            assertThat(event.eventId()).isEqualTo("order-item-cancel-completed:" + orderItemId);
            assertThat(event.billingReleaseRequestId()).isEqualTo("LECTURE-CANCEL-" + orderItemId);
            assertThat(event.usages()).containsExactly(
                    new OrderItemCancelCompletedEvent.Usage(MEMBER_COUPON_ID, RESERVED_GENERATION));
            assertThat(event.budgetRestorations()).isEmpty();
            assertThat(event.memberId()).isEqualTo(BUYER_ID);
            assertThat(event.orderId()).isEqualTo(order.getId());
        });
    }

    @Test
    void 이의_제기_인정_반환이_끝나면_취소_완료_이벤트를_낸다() {
        LectureOrder order = savePaidWithCoupon(DISCOUNT);
        Long orderItemId = itemId(order);
        resolveAccepted(order);
        assertThat(events.cancelCompleted()).isEmpty();

        lectureHpTaskExecutor.execute(jdbcTemplate.queryForObject("select id from lectures.lecture_hp_task "
                + "where purpose = 'RETURN' and order_item_id = ?", Long.class, orderItemId));

        assertThat(events.cancelCompleted()).singleElement().satisfies(event ->
                assertThat(event.billingReleaseRequestId()).isEqualTo("LECTURE-RETURN-" + orderItemId));
    }

    @Test
    void 구매자_금액이_0인_항목의_즉시_완료는_요청_ID_없이_취소_완료_이벤트를_내고_롤백되면_내지_않는다() {
        LectureOrder rolledBack = savePaidWithCoupon(PRICE_HP);
        LectureOrder committed = savePaidWithCoupon(PRICE_HP);

        transactionTemplate.executeWithoutResult(status -> {
            orderCancelUseCase.request(BUYER_ID, rolledBack.getId(), itemId(rolledBack));
            status.setRollbackOnly();
        });
        assertThat(events.cancelCompleted()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("select status from lectures.order_item where id = ?", String.class,
                itemId(rolledBack))).isEqualTo("PURCHASED");

        resolveAccepted(committed);

        assertThat(events.cancelCompleted()).singleElement().satisfies(event -> {
            assertThat(event.orderItemId()).isEqualTo(itemId(committed));
            assertThat(event.billingReleaseRequestId()).isNull();
        });
    }

    /** 판매자 쿠폰 한 장(예약 generation 5)으로 {@code sellerDiscount}만큼 할인해 지금 산 주문 */
    private LectureOrder savePaidWithCoupon(long sellerDiscount) {
        Lecture lecture = lectureRepository.save(onSaleLecture());
        LocalDateTime now = LocalDateTime.now(clock);
        LectureOrder order = LectureOrder.place(BUYER_ID, List.of(lecture), Set.of(), now);
        order.applyItemDiscount(lecture.getId(), sellerDiscount, 0L, sellerDiscount);
        order.markPaid(now);
        LectureOrder saved = lectureOrderRepository.save(order);
        OrderItemCoupon itemCoupon = OrderItemCoupon.create(itemId(saved), MEMBER_COUPON_ID, 11L, "SELLER", 1,
                PRICE_HP, sellerDiscount, 0L, sellerDiscount, RESERVED_GENERATION - 1, 2);
        itemCoupon.recordReserved(RESERVED_GENERATION);
        orderItemCouponRepository.save(itemCoupon);
        return saved;
    }

    private void resolveAccepted(LectureOrder order) {
        LocalDateTime now = LocalDateTime.now(clock);
        transactionTemplate.executeWithoutResult(status -> lectureOrderRepository.findById(order.getId()).orElseThrow()
                .fileObjection(itemId(order), BUYER_ID, INQUIRY_ID, "내용이 다릅니다", now));
        transactionTemplate.executeWithoutResult(status ->
                orderObjectionResolveUseCase.resolveObjection(itemId(order), INQUIRY_ID, true, ADMIN_ID));
    }

    private static Long itemId(LectureOrder order) {
        return order.getItems().getFirst().getId();
    }

    private static CouponCalculateResponse calculated(CouponCalculateRequest request) {
        CouponCalculateRequest.Item item = request.items().getFirst();
        return new CouponCalculateResponse(false, false, List.of(new CouponCalculateResponse.Item(item.lectureId(),
                item.price(), DISCOUNT, 0, DISCOUNT, item.price() - DISCOUNT, item.price() - DISCOUNT,
                List.of(new CouponCalculateResponse.Coupon(MEMBER_COUPON_ID, 11L, "SELLER", 1, item.price(), DISCOUNT,
                        0, DISCOUNT, RESERVED_GENERATION - 1, 2)))), DISCOUNT, item.price() - DISCOUNT);
    }

    private static CouponReserveResponse reserved(CouponReserveRequest request) {
        CouponReserveRequest.Item item = request.items().getFirst();
        return new CouponReserveResponse(request.workId(), "RESERVED", List.of(new CouponReserveResponse.Item(
                item.orderItemId(), DISCOUNT, 0, DISCOUNT, List.of(new CouponReserveResponse.Coupon(MEMBER_COUPON_ID,
                11L, RESERVED_GENERATION, 1, item.price(), DISCOUNT, 0, DISCOUNT, 2)))));
    }

    private Lecture saveOnSale() {
        Lecture lecture = Lecture.register(SELLER_ID, TITLE, DESCRIPTION, PRICE_HP, null, contentSpecs(), NOW);
        lecture.approve(ADMIN_ID, NOW);
        return lectureRepository.save(lecture);
    }
}

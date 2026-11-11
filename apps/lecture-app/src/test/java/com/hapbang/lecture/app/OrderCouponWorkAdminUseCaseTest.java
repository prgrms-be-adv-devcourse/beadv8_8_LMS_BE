package com.hapbang.lecture.app;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.lecture.app.dto.OrderCouponSelection;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.testsupport.billing.RealBillingTestFixture;
import com.hapbang.lecture.fake.FakeCouponApiClient;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.lecture.out.coupon.CouponCallResult;
import com.hapbang.shared.coupon.api.CouponCalculateRequest;
import com.hapbang.shared.coupon.api.CouponCalculateResponse;
import com.hapbang.shared.coupon.api.CouponReleaseRequest;
import com.hapbang.shared.coupon.api.CouponReservationStatusResponse;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 관리자의 쿠폰 작업 조치(다시 시도·결제 실패 확정·해제 확인 완료·예약 상태 조회)를 Fake 쿠폰 Client·실제 billing HTTP와 DB로 확인한다.
 * 상태 전이 규칙은 OrderCouponWorkTest, 예약 상태 판정은 CouponReservationOutcomeTest가 맡는다.
 */
@IntegrationTest
@WithMockUser(roles = "USER")
class OrderCouponWorkAdminUseCaseTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final long SELLER_DISCOUNT = 3_000L;
    private static final long MEMBER_COUPON_ID = 501L;
    private static final long RESERVED_GENERATION = 5L;

    @Autowired
    private OrderCouponWorkAdminUseCase orderCouponWorkAdminUseCase;

    @Autowired
    private OrderCouponWorkUseCase orderCouponWorkUseCase;

    @Autowired
    private LectureOrderFacade lectureOrderFacade;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private RealBillingTestFixture billing;

    @Autowired
    private FakeCouponApiClient coupon;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        billing.reset();
        billing.wallet(BUYER_ID, 1_000_000L, 0L);
        billing.wallet(SELLER_ID, 0L, 0L);

        coupon.reset();
    }

    @Test
    void 다시_시도하면_DEAD_예약_작업이_PENDING이_되고_재전송_배치가_같은_작업_ID로_보낸다() {
        Long orderId = deadReserveOrder();

        orderCouponWorkAdminUseCase.retry(ADMIN_ID, reserveWorkId(orderId));

        assertThat(work(orderId, "RESERVE")).containsEntry("status", "PENDING").containsEntry("attempt_count", 0);
        int callsBefore = coupon.calls().size();
        assertThat(orderCouponWorkUseCase.runDue()).isEqualTo(1);
        assertThat(coupon.calls()).hasSize(callsBefore + 1);
        assertThat(work(orderId, "RESERVE")).containsEntry("attempt_count", 1);
    }

    @Test
    void 다시_시도는_DEAD가_아니거나_주문이_맞는_결제_단계가_아니면_409다() {
        Long orderId = deadReserveOrder();
        jdbcTemplate.update("update lectures.order_coupon_work set status = 'PENDING' where order_id = ?", orderId);
        assertRejected(() -> orderCouponWorkAdminUseCase.retry(ADMIN_ID, reserveWorkId(orderId)),
                LectureErrorCode.COUPON_WORK_NOT_DEAD);

        jdbcTemplate.update("update lectures.order_coupon_work set status = 'DEAD' where order_id = ?", orderId);
        jdbcTemplate.update("update lectures.lecture_order set status = 'HOLD_FAILED' where id = ?", orderId);
        assertRejected(() -> orderCouponWorkAdminUseCase.retry(ADMIN_ID, reserveWorkId(orderId)),
                LectureErrorCode.COUPON_WORK_ORDER_STATE_MISMATCH);
        assertThat(work(orderId, "RESERVE")).containsEntry("status", "DEAD");
        assertRejected(() -> orderCouponWorkAdminUseCase.retry(ADMIN_ID, "lecture-order:999:reserve"),
                LectureErrorCode.COUPON_WORK_NOT_FOUND);
    }

    @Test
    void 결제_실패_확정에서_쿠폰_예약이_남아_있으면_예약_generation을_남기고_주문을_실패로_끝내고_해제를_보낸다() {
        Long orderId = deadReserveOrder();
        coupon.respond(request -> new CouponCallResult.Succeeded<>(status("RESERVED", orderItemId(orderId), "RESERVED")));

        assertThat(orderCouponWorkAdminUseCase.fail(ADMIN_ID, reserveWorkId(orderId)))
                .isEqualTo(OrderCouponWorkAdminUseCase.FailOutcome.RESERVED_RELEASING);

        assertThat(orderStatus(orderId)).isEqualTo("HOLD_FAILED");
        assertThat(work(orderId, "RESERVE")).containsEntry("status", "RESERVED");
        assertThat(jdbcTemplate.queryForObject("select reserved_generation from lectures.order_item_coupon",
                Long.class)).isEqualTo(RESERVED_GENERATION);
        // 해제 작업을 만들고 트랜잭션 밖에서 한 번 보냈다(Fake 해제는 정한 결과가 없으면 결과 불명이라 PENDING)
        assertThat(coupon.calls().getLast().request()).isInstanceOfSatisfying(CouponReleaseRequest.class,
                release -> assertThat(release.usages()).containsExactly(
                        new CouponReleaseRequest.Usage(MEMBER_COUPON_ID, RESERVED_GENERATION)));
        assertThat(coupon.calls().getLast().transactionActive()).isFalse();
        assertThat(work(orderId, "RELEASE")).containsEntry("status", "PENDING").containsEntry("attempt_count", 1);
    }

    @Test
    void 결제_실패_확정에서_쿠폰이_거절했었으면_작업은_거절이고_주문은_실패이며_해제가_없다() {
        Long orderId = deadReserveOrder();
        coupon.respond(request -> new CouponCallResult.Succeeded<>(
                new CouponReservationStatusResponse((String) request, "REJECTED", "COUPON_NOT_USABLE", List.of())));

        assertThat(orderCouponWorkAdminUseCase.fail(ADMIN_ID, reserveWorkId(orderId)))
                .isEqualTo(OrderCouponWorkAdminUseCase.FailOutcome.REJECTED);

        assertThat(orderStatus(orderId)).isEqualTo("HOLD_FAILED");
        assertThat(jdbcTemplate.queryForMap("select status, reject_code from lectures.order_coupon_work "
                + "where order_id = ?", orderId)).containsExactlyInAnyOrderEntriesOf(
                Map.of("status", "REJECTED", "reject_code", "COUPON_NOT_USABLE"));
        assertThat(releaseCount()).isZero();
    }

    @Test
    void 결제_실패_확정에서_쿠폰_예약이_없으면_작업은_거절로_닫히고_주문은_실패이며_해제가_없다() {
        Long orderId = deadReserveOrder();
        coupon.respond(request -> new CouponCallResult.Succeeded<>(
                new CouponReservationStatusResponse((String) request, "NOT_FOUND", null, List.of())));

        assertThat(orderCouponWorkAdminUseCase.fail(ADMIN_ID, reserveWorkId(orderId)))
                .isEqualTo(OrderCouponWorkAdminUseCase.FailOutcome.NOT_RESERVED);

        assertThat(orderStatus(orderId)).isEqualTo("HOLD_FAILED");
        assertThat(work(orderId, "RESERVE")).containsEntry("status", "REJECTED")
                .containsEntry("reject_code", OrderCouponWorkAdminUseCase.ADMIN_FAILED_NOT_RESERVED);
        assertThat(releaseCount()).isZero();
        // 닫혔으므로 DEAD 목록에 다시 나오지 않는다
        assertThat(jdbcTemplate.queryForObject("select count(*) from lectures.order_coupon_work where order_id = ? "
                + "and status = 'DEAD'", Long.class, orderId)).isZero();
    }

    @Test
    void 결제_실패_확정은_결제_완료된_사용_시도가_보이거나_조회할_수_없으면_아무것도_바꾸지_않는다() {
        Long orderId = deadReserveOrder();
        coupon.respond(request -> new CouponCallResult.Succeeded<>(status("RESERVED", orderItemId(orderId), "PAID")));
        coupon.respond(request -> new CouponCallResult.Unknown<>(0, "응답 시간 초과"));
        coupon.respond(request -> new CouponCallResult.Rejected<>(401, null, null, null));

        assertRejected(() -> orderCouponWorkAdminUseCase.fail(ADMIN_ID, reserveWorkId(orderId)),
                LectureErrorCode.COUPON_RESERVATION_SETTLED);
        assertRejected(() -> orderCouponWorkAdminUseCase.fail(ADMIN_ID, reserveWorkId(orderId)),
                LectureErrorCode.COUPON_UNAVAILABLE);
        assertRejected(() -> orderCouponWorkAdminUseCase.fail(ADMIN_ID, reserveWorkId(orderId)),
                LectureErrorCode.COUPON_UNAVAILABLE);

        assertThat(orderStatus(orderId)).isEqualTo("HOLD_PENDING");
        assertThat(work(orderId, "RESERVE")).containsEntry("status", "DEAD");
        assertThat(releaseCount()).isZero();
    }

    @Test
    void 결제_실패_확정은_주문이_결제_처리_중이_아니면_쿠폰을_부르지_않고_409다() {
        Long orderId = deadReserveOrder();
        jdbcTemplate.update("update lectures.lecture_order set status = 'HOLD_FAILED' where id = ?", orderId);
        int callsBefore = coupon.calls().size();

        assertRejected(() -> orderCouponWorkAdminUseCase.fail(ADMIN_ID, reserveWorkId(orderId)),
                LectureErrorCode.COUPON_WORK_ORDER_STATE_MISMATCH);

        assertThat(coupon.calls()).hasSize(callsBefore);
        assertThat(work(orderId, "RESERVE")).containsEntry("status", "DEAD");
    }

    @Test
    void 결제_실패_확정이_쿠폰을_조회하는_사이_다른_관리자가_다시_시도하면_작업이_바뀌어_기록하지_않고_409다() {
        Long orderId = deadReserveOrder();
        coupon.respond(request -> {
            // fail이 트랜잭션 밖에서 쿠폰 예약 상태를 기다리는 사이 다른 관리자의 다시 시도가 커밋된다
            orderCouponWorkAdminUseCase.retry(ADMIN_ID + 1, reserveWorkId(orderId));
            return new CouponCallResult.Succeeded<>(
                    new CouponReservationStatusResponse((String) request, "NOT_FOUND", null, List.of()));
        });

        assertRejected(() -> orderCouponWorkAdminUseCase.fail(ADMIN_ID, reserveWorkId(orderId)),
                LectureErrorCode.COUPON_WORK_CHANGED);

        // 다시 시도가 이겼다: 주문은 결제 처리 중 그대로이고 작업은 재전송 대기다
        assertThat(orderStatus(orderId)).isEqualTo("HOLD_PENDING");
        assertThat(work(orderId, "RESERVE")).containsEntry("status", "PENDING").containsEntry("attempt_count", 0);
    }

    @Test
    void 결제_실패_확정이_쿠폰을_조회한_뒤_작업이_다시_DEAD가_돼도_그사이_바뀌었으면_기록하지_않고_409다() {
        Long orderId = deadReserveOrder();
        coupon.respond(request -> {
            // 조회하는 사이 다시 시도 → 재전송 → 다시 DEAD가 된 것처럼 상태는 DEAD 그대로이고 버전만 오른다
            jdbcTemplate.update("update lectures.order_coupon_work set version = version + 2 where order_id = ? "
                    + "and kind = 'RESERVE'", orderId);
            return new CouponCallResult.Succeeded<>(
                    new CouponReservationStatusResponse((String) request, "NOT_FOUND", null, List.of()));
        });

        assertRejected(() -> orderCouponWorkAdminUseCase.fail(ADMIN_ID, reserveWorkId(orderId)),
                LectureErrorCode.COUPON_WORK_CHANGED);

        // 조회 때 본 "예약 없음"으로 결제 실패를 확정하지 않는다
        assertThat(orderStatus(orderId)).isEqualTo("HOLD_PENDING");
        assertThat(work(orderId, "RESERVE")).containsEntry("status", "DEAD");
    }

    @Test
    void 결제_실패_확정은_아직_예약된_사용_시도의_generation만_남겨_해제한다() {
        Long orderId = deadReserveOrder();
        Long orderItemId = orderItemId(orderId);
        coupon.respond(request -> new CouponCallResult.Succeeded<>(new CouponReservationStatusResponse("ignored",
                "RESERVED", null, List.of(new CouponReservationStatusResponse.Item(orderItemId, SELLER_DISCOUNT, 0L,
                SELLER_DISCOUNT, List.of(usage(RESERVED_GENERATION - 2, "RELEASED"),
                usage(RESERVED_GENERATION, "RESERVED")))))));

        assertThat(orderCouponWorkAdminUseCase.fail(ADMIN_ID, reserveWorkId(orderId)))
                .isEqualTo(OrderCouponWorkAdminUseCase.FailOutcome.RESERVED_RELEASING);

        assertThat(jdbcTemplate.queryForObject("select reserved_generation from lectures.order_item_coupon",
                Long.class)).isEqualTo(RESERVED_GENERATION);
        assertThat(coupon.calls().getLast().request()).isInstanceOfSatisfying(CouponReleaseRequest.class,
                release -> assertThat(release.usages()).containsExactly(
                        new CouponReleaseRequest.Usage(MEMBER_COUPON_ID, RESERVED_GENERATION)));
    }

    @Test
    void 결제_실패_확정_뒤_해제를_바로_보내다_예외가_나도_확정_결과를_돌려주고_해제_작업은_남는다() {
        Long orderId = deadReserveOrder();
        coupon.respond(request -> new CouponCallResult.Succeeded<>(status("RESERVED", orderItemId(orderId), "RESERVED")));
        coupon.respond(request -> {
            throw new IllegalStateException("해제 호출 예외 확인");
        });

        assertThat(orderCouponWorkAdminUseCase.fail(ADMIN_ID, reserveWorkId(orderId)))
                .isEqualTo(OrderCouponWorkAdminUseCase.FailOutcome.RESERVED_RELEASING);

        assertThat(orderStatus(orderId)).isEqualTo("HOLD_FAILED");
        assertThat(work(orderId, "RELEASE")).containsEntry("status", "PENDING");
    }

    @Test
    void 결제_실패_확정은_계산_근거에_없는_예약_사용_시도가_있으면_따로_구분되는_사유로_409다() {
        Long orderId = deadReserveOrder();
        coupon.respond(request -> new CouponCallResult.Succeeded<>(new CouponReservationStatusResponse("ignored",
                "RESERVED", null, List.of(new CouponReservationStatusResponse.Item(orderItemId(orderId) + 1000,
                SELLER_DISCOUNT, 0L, SELLER_DISCOUNT, List.of(usage(RESERVED_GENERATION, "RESERVED")))))));

        assertThatThrownBy(() -> orderCouponWorkAdminUseCase.fail(ADMIN_ID, reserveWorkId(orderId)))
                .isInstanceOfSatisfying(LectureException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(LectureErrorCode.COUPON_RESERVATION_SETTLED);
                    assertThat(e.getMessage()).contains("계산 근거에 없는 사용 시도");
                });
        assertThat(orderStatus(orderId)).isEqualTo("HOLD_PENDING");
        assertThat(work(orderId, "RESERVE")).containsEntry("status", "DEAD");
    }

    @Test
    void 해제_확인_완료는_쿠폰_예약이_남지_않았을_때만_해제_작업을_닫는다() {
        Long orderId = deadReserveOrder();
        coupon.respond(request -> new CouponCallResult.Succeeded<>(status("RESERVED", orderItemId(orderId), "RESERVED")));
        orderCouponWorkAdminUseCase.fail(ADMIN_ID, reserveWorkId(orderId));
        String releaseWorkId = "lecture-order:" + orderId + ":release";
        jdbcTemplate.update("update lectures.order_coupon_work set status = 'DEAD' where work_id = ?", releaseWorkId);
        coupon.respond(request -> new CouponCallResult.Succeeded<>(status("RESERVED", orderItemId(orderId), "RESERVED")));
        coupon.respond(request -> new CouponCallResult.Succeeded<>(status("RELEASED", orderItemId(orderId), "RELEASED")));

        assertRejected(() -> orderCouponWorkAdminUseCase.resolve(ADMIN_ID, releaseWorkId),
                LectureErrorCode.COUPON_RESERVATION_STILL_HELD);
        assertThat(work(orderId, "RELEASE")).containsEntry("status", "DEAD");
        orderCouponWorkAdminUseCase.resolve(ADMIN_ID, releaseWorkId);

        assertThat(work(orderId, "RELEASE")).containsEntry("status", "RELEASED");
        // 해제 확인 완료는 해제 작업에만 쓴다
        assertRejected(() -> orderCouponWorkAdminUseCase.resolve(ADMIN_ID, reserveWorkId(orderId)),
                LectureErrorCode.COUPON_WORK_ACTION_NOT_ALLOWED);
    }

    @Test
    void 상세용_예약_상태는_쿠폰을_부르지_못해도_UNAVAILABLE로_준다() {
        Long orderId = deadReserveOrder();
        coupon.respond(request -> new CouponCallResult.Succeeded<>(status("RESERVED", orderItemId(orderId), "RESERVED")));
        coupon.respond(request -> new CouponCallResult.NotSent<>("회로 열림"));

        assertThat(orderCouponWorkAdminUseCase.reservationStatus(reserveWorkId(orderId)).couponStatus())
                .isEqualTo("RESERVED");
        assertThat(orderCouponWorkAdminUseCase.reservationStatus(reserveWorkId(orderId)))
                .isEqualTo(new OrderCouponWorkAdminUseCase.CouponReservationView(
                        OrderCouponWorkAdminUseCase.COUPON_STATUS_UNAVAILABLE, null));
    }

    /** 쿠폰 예약 결과를 몰라 시도 한도에 닿아 멈춘 예약 작업의 주문(결제 처리 중) */
    private Long deadReserveOrder() {
        Lecture lecture = lectureRepository.save(onSale());
        coupon.respond(request -> new CouponCallResult.Succeeded<>(calculated((CouponCalculateRequest) request)));
        Long orderId = lectureOrderFacade.createOrder(BUYER_ID, List.of(lecture.getId()),
                List.of(new OrderCouponSelection(MEMBER_COUPON_ID, lecture.getId())), PRICE_HP - SELLER_DISCOUNT)
                .orderId();
        jdbcTemplate.update("update lectures.order_coupon_work set status = 'DEAD' where order_id = ?", orderId);
        return orderId;
    }

    private static CouponReservationStatusResponse status(String status, Long orderItemId, String usageStatus) {
        return new CouponReservationStatusResponse("ignored", status, null, List.of(
                new CouponReservationStatusResponse.Item(orderItemId, SELLER_DISCOUNT, 0L, SELLER_DISCOUNT, List.of(
                        new CouponReservationStatusResponse.Coupon(MEMBER_COUPON_ID, 11L, RESERVED_GENERATION, 1,
                                PRICE_HP, SELLER_DISCOUNT, 0L, SELLER_DISCOUNT, 2, usageStatus)))));
    }

    private static CouponReservationStatusResponse.Coupon usage(long generation, String usageStatus) {
        return new CouponReservationStatusResponse.Coupon(MEMBER_COUPON_ID, 11L, generation, 1, PRICE_HP,
                SELLER_DISCOUNT, 0L, SELLER_DISCOUNT, 2, usageStatus);
    }

    private static CouponCalculateResponse calculated(CouponCalculateRequest request) {
        CouponCalculateRequest.Item item = request.items().getFirst();
        return new CouponCalculateResponse(false, false, List.of(new CouponCalculateResponse.Item(item.lectureId(),
                item.price(), SELLER_DISCOUNT, 0, SELLER_DISCOUNT, item.price() - SELLER_DISCOUNT,
                item.price() - SELLER_DISCOUNT, List.of(new CouponCalculateResponse.Coupon(MEMBER_COUPON_ID, 11L,
                "SELLER", 1, item.price(), SELLER_DISCOUNT, 0, SELLER_DISCOUNT, RESERVED_GENERATION - 1, 2)))),
                SELLER_DISCOUNT, item.price() - SELLER_DISCOUNT);
    }

    private static String reserveWorkId(Long orderId) {
        return "lecture-order:" + orderId + ":reserve";
    }

    private Long orderItemId(Long orderId) {
        return jdbcTemplate.queryForObject("select id from lectures.order_item where order_id = ?", Long.class, orderId);
    }

    private Map<String, Object> work(Long orderId, String kind) {
        return jdbcTemplate.queryForMap("select status, attempt_count, reject_code from lectures.order_coupon_work "
                + "where order_id = ? and kind = ?", orderId, kind);
    }

    private String orderStatus(Long orderId) {
        return jdbcTemplate.queryForObject("select status from lectures.lecture_order where id = ?", String.class,
                orderId);
    }

    private long releaseCount() {
        return jdbcTemplate.queryForObject("select count(*) from lectures.order_coupon_work where kind = 'RELEASE'",
                Long.class);
    }

    private static void assertRejected(ThrowingCallable action, LectureErrorCode errorCode) {
        assertThatThrownBy(action).isInstanceOf(LectureException.class).extracting("errorCode").isEqualTo(errorCode);
    }

    private static Lecture onSale() {
        Lecture lecture = Lecture.register(SELLER_ID, TITLE, DESCRIPTION, PRICE_HP, null, contentSpecs(), NOW);
        lecture.approve(ADMIN_ID, NOW);
        return lecture;
    }
}

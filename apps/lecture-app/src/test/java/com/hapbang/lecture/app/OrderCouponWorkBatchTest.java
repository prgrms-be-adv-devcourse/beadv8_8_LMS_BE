package com.hapbang.lecture.app;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.lecture.app.dto.OrderCouponSelection;
import com.hapbang.lecture.app.dto.OrderResponse;
import com.hapbang.lecture.config.LectureOrderProperties;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.domain.LectureOrderStatus;
import com.hapbang.lecture.fake.FakeBillingApiClient;
import com.hapbang.lecture.fake.FakeCouponClient;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.lecture.out.coupon.CouponCallResult;
import com.hapbang.shared.billing.domain.BillingFailureCode;
import com.hapbang.shared.billing.out.BillingCallResult;
import com.hapbang.shared.coupon.api.CouponCalculateRequest;
import com.hapbang.shared.coupon.api.CouponCalculateResponse;
import com.hapbang.shared.coupon.api.CouponReleaseRequest;
import com.hapbang.shared.coupon.api.CouponReleaseResponse;
import com.hapbang.shared.coupon.api.CouponReserveRequest;
import com.hapbang.shared.coupon.api.CouponReserveResponse;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 결제 실패한 쿠폰 주문의 쿠폰 해제 작업과 쿠폰 작업 재전송 배치(시도 한도·DEAD·회로 열림)를 Fake 쿠폰·지갑 Client와 DB로 확인한다.
 * 작업 상태 전이는 OrderCouponWorkTest(도메인)가 맡는다.
 */
@IntegrationTest
@WithMockUser(roles = "USER")
@ExtendWith(OutputCaptureExtension.class)
class OrderCouponWorkBatchTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final long SELLER_DISCOUNT = 3_000L;
    private static final long RESERVED_GENERATION = 5L;

    @Autowired
    private LectureOrderFacade lectureOrderFacade;

    @Autowired
    private OrderCouponWorkUseCase orderCouponWorkUseCase;

    @Autowired
    private OrderCouponReserveUseCase orderCouponReserveUseCase;

    @Autowired
    private OrderCouponReleaseUseCase orderCouponReleaseUseCase;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private FakeBillingApiClient billing;

    @Autowired
    private FakeCouponClient coupon;

    @Autowired
    private LectureOrderProperties properties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        billing.reset();
        billing.setSettledHp(PRICE_HP);
        coupon.reset();
    }

    @Test
    void 예약_뒤_보류가_거절되어_결제_실패로_끝나면_같은_트랜잭션에서_해제_작업을_만들고_예약_generation으로_해제한다() {
        Lecture lecture = saveOnSale();
        respondCalculatedAndReserved(lecture);
        billing.respond(command -> new BillingCallResult.Failed<>(BillingFailureCode.INSUFFICIENT_BALANCE,
                "HP 잔액이 부족합니다.", 409, "INSUFFICIENT_BALANCE"));
        coupon.respond(request -> new CouponCallResult.Succeeded<>(released((CouponReleaseRequest) request)));

        assertThatThrownBy(() -> order(lecture))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.INSUFFICIENT_BALANCE);

        Long orderId = onlyOrderId();
        assertThat(orderStatus(orderId)).isEqualTo("HOLD_FAILED");
        assertThat(coupon.calls().get(2).request()).isEqualTo(new CouponReleaseRequest(orderId,
                "lecture-order:" + orderId + ":release", List.of(new CouponReleaseRequest.Usage(501L,
                RESERVED_GENERATION)), OrderCouponReleaseUseCase.RELEASE_REASON, null));
        assertThat(coupon.calls().get(2).transactionActive()).isFalse();
        assertThat(work(orderId, "RELEASE")).containsEntry("status", "RELEASED");
    }

    @Test
    void 쿠폰_없는_주문은_결제_실패해도_쿠폰_작업을_만들지_않는다() {
        Lecture lecture = saveOnSale();
        billing.respond(command -> new BillingCallResult.Failed<>(BillingFailureCode.INSUFFICIENT_BALANCE,
                "HP 잔액이 부족합니다.", 409, "INSUFFICIENT_BALANCE"));

        assertThatThrownBy(() -> lectureOrderFacade.createOrder(BUYER_ID, List.of(lecture.getId())))
                .isInstanceOf(LectureException.class);

        assertThat(jdbcTemplate.queryForObject("select count(*) from lectures.order_coupon_work", Long.class)).isZero();
        assertThat(coupon.calls()).isEmpty();
    }

    @Test
    void 결과를_모른_예약은_다음_시도_시각이_지나면_배치가_같은_workId로_다시_보내고_성공하면_보류_작업을_등록한다() {
        Lecture lecture = saveOnSale();
        coupon.respond(request -> new CouponCallResult.Succeeded<>(calculated((CouponCalculateRequest) request)));
        OrderResponse response = order(lecture);
        assertThat(response.status()).isEqualTo(LectureOrderStatus.HOLD_PENDING);
        Long orderId = response.orderId();

        // 다음 시도 시각 전에는 다시 보내지 않는다
        assertThat(orderCouponWorkUseCase.runDue()).isZero();
        makeDue(orderId);
        coupon.respond(request -> new CouponCallResult.Succeeded<>(reserved((CouponReserveRequest) request)));

        assertThat(orderCouponWorkUseCase.runDue()).isEqualTo(1);

        List<FakeCouponClient.Call> reserves = coupon.calls().stream()
                .filter(call -> call.request() instanceof CouponReserveRequest).toList();
        assertThat(reserves).hasSize(2).extracting(call -> call.request()).containsOnly(reserves.getFirst().request());
        assertThat(work(orderId, "RESERVE")).containsEntry("status", "RESERVED").containsEntry("attempt_count", 2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from lectures.lecture_hp_task where order_id = ? "
                + "and purpose = 'ORDER_HOLD'", Long.class, orderId)).isEqualTo(1);
    }

    @Test
    void 결과를_모른_예약이_시도_한도에_닿으면_보내지_않고_DEAD로_멈추며_시간만으로_해제하지_않는다() {
        Lecture lecture = saveOnSale();
        coupon.respond(request -> new CouponCallResult.Succeeded<>(calculated((CouponCalculateRequest) request)));
        Long orderId = order(lecture).orderId();
        jdbcTemplate.update("update lectures.order_coupon_work set attempt_count = ? where order_id = ?",
                properties.couponWorkMaxAttempts(), orderId);
        makeDue(orderId);
        int callsBefore = coupon.calls().size();

        orderCouponWorkUseCase.runDue();

        assertThat(coupon.calls()).hasSize(callsBefore);
        assertThat(work(orderId, "RESERVE")).containsEntry("status", "DEAD");
        assertThat(orderStatus(orderId)).isEqualTo("HOLD_PENDING");
        assertThat(jdbcTemplate.queryForObject("select count(*) from lectures.order_coupon_work where kind = 'RELEASE'",
                Long.class)).isZero();
    }

    @Test
    void 쿠폰이_해제를_거절하면_다시_보내도_같으므로_DEAD로_멈춘다() {
        Lecture lecture = saveOnSale();
        respondCalculatedAndReserved(lecture);
        billing.respond(command -> new BillingCallResult.Failed<>(BillingFailureCode.INSUFFICIENT_BALANCE,
                "HP 잔액이 부족합니다.", 409, "INSUFFICIENT_BALANCE"));
        coupon.respond(request -> new CouponCallResult.Rejected<>(409, "COUPON_RELEASE_NOT_ALLOWED", null, null));

        assertThatThrownBy(() -> order(lecture)).isInstanceOf(LectureException.class);

        assertThat(work(onlyOrderId(), "RELEASE")).containsEntry("status", "DEAD");
    }

    @Test
    void 다른_실행이_이미_시도를_기록한_예약_해제_작업은_다음_시도_시각_전에_다시_보내지_않는다() {
        // 예약: 주문 요청의 첫 시도가 결과를 몰라 다음 시도 시각이 미래다
        Lecture lecture = saveOnSale();
        coupon.respond(request -> new CouponCallResult.Succeeded<>(calculated((CouponCalculateRequest) request)));
        Long reserveOrderId = order(lecture).orderId();
        // 해제: 결제 실패 뒤 주문 요청 안의 첫 해제가 결과를 몰라 다음 시도 시각이 미래다
        Lecture failing = saveOnSale();
        respondCalculatedAndReserved(failing);
        billing.respond(command -> new BillingCallResult.Failed<>(BillingFailureCode.INSUFFICIENT_BALANCE,
                "HP 잔액이 부족합니다.", 409, "INSUFFICIENT_BALANCE"));
        assertThatThrownBy(() -> order(failing)).isInstanceOf(LectureException.class);
        Long releaseOrderId = jdbcTemplate.queryForObject("select order_id from lectures.order_coupon_work "
                + "where kind = 'RELEASE'", Long.class);
        int callsBefore = coupon.calls().size();

        // findDue로 고른 뒤 다른 실행이 시도를 커밋한 경우와 같다(배치가 고른 목록이 낡았다)
        orderCouponReserveUseCase.reserve(reserveOrderId);
        orderCouponReleaseUseCase.release(releaseOrderId);

        assertThat(coupon.calls()).hasSize(callsBefore);
        assertThat(work(reserveOrderId, "RESERVE")).containsEntry("status", "PENDING").containsEntry("attempt_count", 1);
        assertThat(work(releaseOrderId, "RELEASE")).containsEntry("status", "PENDING").containsEntry("attempt_count", 1);
    }

    @Test
    void 마지막_시도가_진행_중일_때_배치가_돌아도_DEAD가_되지_않고_그_시도의_성공이_기록된다() {
        Lecture lecture = saveOnSale();
        coupon.respond(request -> new CouponCallResult.Succeeded<>(calculated((CouponCalculateRequest) request)));
        coupon.respond(request -> {
            // 주문 요청의 예약 시도가 쿠폰 응답을 기다리는 사이: 이 시도가 마지막 허용 시도이고, 낡은 목록으로 고른 배치가 같은 작업을 집는다
            CouponReserveRequest reserve = (CouponReserveRequest) request;
            jdbcTemplate.update("update lectures.order_coupon_work set attempt_count = ? where order_id = ?",
                    properties.couponWorkMaxAttempts(), reserve.orderId());
            orderCouponReserveUseCase.reserve(reserve.orderId());
            assertThat(work(reserve.orderId(), "RESERVE")).containsEntry("status", "PENDING");
            return new CouponCallResult.Succeeded<>(reserved(reserve));
        });

        OrderResponse response = order(lecture);

        // 진행 중이던 시도의 성공이 그대로 기록되어 보류·결제까지 이어졌다
        assertThat(response.status()).isEqualTo(LectureOrderStatus.PAID);
        assertThat(work(response.orderId(), "RESERVE")).containsEntry("status", "RESERVED");
        assertThat(coupon.calls().stream().filter(call -> call.request() instanceof CouponReserveRequest)).hasSize(1);
    }

    @Test
    void 다시_보내다_예외가_나면_alert_표식을_단_ERROR를_남기고_다음_작업으로_넘어간다(CapturedOutput output) {
        Lecture lecture = saveOnSale();
        coupon.respond(request -> new CouponCallResult.Succeeded<>(calculated((CouponCalculateRequest) request)));
        Long orderId = order(lecture).orderId();
        makeDue(orderId);
        coupon.respond(request -> {
            throw new IllegalStateException("재전송 예외 확인");
        });

        assertThat(orderCouponWorkUseCase.runDue()).isEqualTo(1);

        assertThat(output.getAll().lines().filter(line -> line.contains("[LECTURE_COUPON_WORK_RETRY_FAILED]")))
                .singleElement().satisfies(line -> assertThat(line).contains(" ERROR ", "orderId=" + orderId,
                        "workId=lecture-order:" + orderId + ":reserve"));
    }

    @Test
    void 쿠폰_회로가_열려_있으면_배치는_작업을_보내지_않고_시도_횟수를_쓰지_않는다() {
        Lecture lecture = saveOnSale();
        coupon.respond(request -> new CouponCallResult.Succeeded<>(calculated((CouponCalculateRequest) request)));
        Long orderId = order(lecture).orderId();
        makeDue(orderId);
        coupon.setCircuitOpen(true);

        assertThat(orderCouponWorkUseCase.runDue()).isZero();

        assertThat(work(orderId, "RESERVE")).containsEntry("status", "PENDING").containsEntry("attempt_count", 1);
    }

    private OrderResponse order(Lecture lecture) {
        return lectureOrderFacade.createOrder(BUYER_ID, List.of(lecture.getId()),
                List.of(new OrderCouponSelection(501L, lecture.getId())), PRICE_HP - SELLER_DISCOUNT);
    }

    private void respondCalculatedAndReserved(Lecture lecture) {
        coupon.respond(request -> new CouponCallResult.Succeeded<>(calculated((CouponCalculateRequest) request)));
        coupon.respond(request -> new CouponCallResult.Succeeded<>(reserved((CouponReserveRequest) request)));
    }

    private void makeDue(Long orderId) {
        jdbcTemplate.update("update lectures.order_coupon_work set next_attempt_at = next_attempt_at - interval '1 hour' "
                + "where order_id = ?", orderId);
    }

    private Map<String, Object> work(Long orderId, String kind) {
        return jdbcTemplate.queryForMap("select status, attempt_count from lectures.order_coupon_work "
                + "where order_id = ? and kind = ?", orderId, kind);
    }

    private String orderStatus(Long orderId) {
        return jdbcTemplate.queryForObject("select status from lectures.lecture_order where id = ?", String.class,
                orderId);
    }

    private Long onlyOrderId() {
        return jdbcTemplate.queryForObject("select id from lectures.lecture_order", Long.class);
    }

    /** 첫 강의에 판매자 쿠폰 501 한 장(판매자 부담 3,000 HP) */
    private static CouponCalculateResponse calculated(CouponCalculateRequest request) {
        CouponCalculateRequest.Item item = request.items().getFirst();
        return new CouponCalculateResponse(false, false, List.of(new CouponCalculateResponse.Item(item.lectureId(),
                item.price(), SELLER_DISCOUNT, 0, SELLER_DISCOUNT, item.price() - SELLER_DISCOUNT,
                item.price() - SELLER_DISCOUNT, List.of(new CouponCalculateResponse.Coupon(501L, 11L, "SELLER", 1,
                item.price(), SELLER_DISCOUNT, 0, SELLER_DISCOUNT, RESERVED_GENERATION - 1, 2)))),
                SELLER_DISCOUNT, item.price() - SELLER_DISCOUNT);
    }

    private static CouponReserveResponse reserved(CouponReserveRequest request) {
        CouponReserveRequest.Item item = request.items().getFirst();
        return new CouponReserveResponse(request.workId(), "RESERVED", List.of(new CouponReserveResponse.Item(
                item.orderItemId(), SELLER_DISCOUNT, 0, SELLER_DISCOUNT, List.of(new CouponReserveResponse.Coupon(501L,
                11L, RESERVED_GENERATION, 1, item.price(), SELLER_DISCOUNT, 0, SELLER_DISCOUNT, 2)))));
    }

    private static CouponReleaseResponse released(CouponReleaseRequest request) {
        return new CouponReleaseResponse(request.workId(), request.usages().stream()
                .map(usage -> new CouponReleaseResponse.Usage(usage.memberCouponId(), usage.generation(), "AVAILABLE"))
                .toList());
    }

    private Lecture saveOnSale() {
        Lecture lecture = Lecture.register(SELLER_ID, TITLE, DESCRIPTION, PRICE_HP, null, contentSpecs(), NOW);
        lecture.approve(ADMIN_ID, NOW);
        return lectureRepository.save(lecture);
    }
}

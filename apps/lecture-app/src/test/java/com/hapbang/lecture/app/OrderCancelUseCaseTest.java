package com.hapbang.lecture.app;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.lecture.app.dto.OrderItemResponse;
import com.hapbang.lecture.app.dto.OrderResponse;
import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.LectureOrderStatus;
import com.hapbang.lecture.domain.OrderItemStatus;
import com.hapbang.lecture.domain.ProcessingStatus;
import com.hapbang.lecture.fake.FakeBillingApiClient;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.out.BillingCallExceptions;
import com.hapbang.shared.billing.out.BillingCallFailureKind;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 구매 취소를 HP 반환(Fake billing)과 DB까지 확인한다. 기한 경계는 LectureOrderTest가 맡는다.
 */
/**
 * 구매 취소 (D59 (1)·(4)). 구매 취소 처리 중과 RELEASE 작업을 커밋한 뒤 트랜잭션 밖에서 반환을 한 번 시도한다.
 */
@IntegrationTest
@WithMockUser(roles = "USER")
class OrderCancelUseCaseTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final Long OTHER_BUYER_ID = 4L;

    @Autowired
    private LectureOrderFacade lectureOrderFacade;

    @Autowired
    private LectureHpTaskBatchUseCase lectureHpTaskBatchUseCase;

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private FakeBillingApiClient billing;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier(LectureConfig.CLOCK)
    private Clock clock;

    @BeforeEach
    void setUp() {
        billing.reset();
        billing.setSettledHp(PRICE_HP);
    }

    @Test
    void 구매_취소하면_트랜잭션_밖에서_그_항목의_보류_HP를_전부_반환하고_구매_취소가_된다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"), saveOnSale("마이크 고르기"));
        Long canceledItemId = order.items().getFirst().orderItemId();
        billing.reset();
        billing.setSettledHp(PRICE_HP);

        OrderItemResponse response = lectureOrderFacade.cancel(BUYER_ID, order.orderId(), canceledItemId);

        assertThat(response.status()).isEqualTo(OrderItemStatus.CANCELED);
        assertThat(response.canceledAt()).isNotNull();
        assertThat(response.processingStatus()).isNull();
        assertThat(billing.calls()).singleElement().satisfies(call -> {
            assertThat(call.transactionActive()).isFalse();
            ReleaseCommand release = (ReleaseCommand) call.command();
            assertThat(release.userId()).isEqualTo(BUYER_ID);
            assertThat(release.referenceType()).isEqualTo(BillingReferenceType.LECTURE_ORDER_ITEM);
            assertThat(release.referenceId()).isEqualTo(canceledItemId);
            assertThat(release.releaseRequestId()).isEqualTo("LECTURE-CANCEL-" + canceledItemId);
            assertThat(release.isFull()).isTrue();
        });
        OrderResponse saved = lectureOrderFacade.getOrder(BUYER_ID, order.orderId());
        assertThat(saved.status()).isEqualTo(LectureOrderStatus.PAID);
        assertThat(saved.items()).extracting(OrderItemResponse::status)
                .containsExactly(OrderItemStatus.CANCELED, OrderItemStatus.PURCHASED);
    }

    @Test
    void 반환_결과를_모르면_구매_취소_처리_중이고_배치가_구매_취소로_마무리한다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long orderItemId = order.items().getFirst().orderItemId();
        billing.respond(command -> {
            throw BillingCallExceptions.of(BillingCallFailureKind.UNKNOWN_RESULT, 0, null);
        });

        OrderItemResponse response = lectureOrderFacade.cancel(BUYER_ID, order.orderId(), orderItemId);

        // 요청한 순간부터 볼 수 없고, 반환이 끝나야 구매 취소다 (D59 (1)·(2))
        assertThat(response.status()).isEqualTo(OrderItemStatus.CANCEL_PENDING);
        assertThat(response.processingStatus()).isEqualTo(ProcessingStatus.DELAYED);
        assertThat(response.canceledAt()).isNull();

        runDueTasks();

        OrderResponse saved = lectureOrderFacade.getOrder(BUYER_ID, order.orderId());
        assertThat(saved.items()).extracting(OrderItemResponse::status).containsExactly(OrderItemStatus.CANCELED);
        assertThat(saved.status()).isEqualTo(LectureOrderStatus.CANCELED);
    }

    @Test
    void 반환_결과를_기록하지_못해도_같은_요청_ID로_다시_반환해_한_번만_구매_취소가_된다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long orderItemId = order.items().getFirst().orderItemId();
        billing.reset();
        billing.setSettledHp(PRICE_HP);
        // 반환하는 동안 다른 실행이 선점을 가져가, 첫 결과는 늦은 결과로 버려진다
        billing.setDuringCall(() -> jdbcTemplate.update("""
                update lectures.lecture_hp_task set claim_token = gen_random_uuid()
                where purpose = 'CANCEL' and status = 'PROCESSING'"""));

        OrderItemResponse response = lectureOrderFacade.cancel(BUYER_ID, order.orderId(), orderItemId);
        billing.setDuringCall(() -> {
        });
        assertThat(response.status()).isEqualTo(OrderItemStatus.CANCEL_PENDING);

        runDueTasks();

        assertThat(itemStatus(order.orderId())).isEqualTo(OrderItemStatus.CANCELED);
        assertThat(billing.calls()).hasSize(2)
                .extracting(call -> ((ReleaseCommand) call.command()).releaseRequestId())
                .containsOnly("LECTURE-CANCEL-" + orderItemId);
    }

    @Test
    void 마지막_항목을_구매_취소하면_주문도_취소된다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"), saveOnSale("마이크 고르기"));

        for (OrderItemResponse item : order.items()) {
            lectureOrderFacade.cancel(BUYER_ID, order.orderId(), item.orderItemId());
        }

        OrderResponse saved = lectureOrderFacade.getOrder(BUYER_ID, order.orderId());
        assertThat(saved.status()).isEqualTo(LectureOrderStatus.CANCELED);
        assertThat(saved.canceledAt()).isNotNull();
    }

    @Test
    void 마지막_두_항목을_동시에_구매_취소해도_주문이_취소된다() throws Exception {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"), saveOnSale("마이크 고르기"));
        // 두 반환이 모두 지갑 호출에 들어갈 때까지 기다리게 해, 결과 기록이 주문을 잠그지 않으면 서로의 취소를 보지 못하게 한다
        CyclicBarrier bothReleasing = new CyclicBarrier(2);
        billing.setDuringCall(() -> await(bothReleasing));
        SecurityContext securityContext = SecurityContextHolder.getContext();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<OrderItemResponse>> futures = order.items().stream()
                    .map(item -> executor.submit(() -> {
                        SecurityContextHolder.setContext(securityContext);
                        try {
                            return lectureOrderFacade.cancel(BUYER_ID, order.orderId(), item.orderItemId());
                        } finally {
                            SecurityContextHolder.clearContext();
                        }
                    }))
                    .toList();
            for (Future<OrderItemResponse> future : futures) {
                assertThat(future.get().status()).isEqualTo(OrderItemStatus.CANCELED);
            }
        }

        OrderResponse saved = lectureOrderFacade.getOrder(BUYER_ID, order.orderId());
        assertThat(saved.items()).extracting(OrderItemResponse::status).containsOnly(OrderItemStatus.CANCELED);
        assertThat(saved.status()).isEqualTo(LectureOrderStatus.CANCELED);
    }

    @Test
    void 반환액이_항목_가격과_다르면_구매_취소_처리_중에_멈추고_관리자_확인이_필요하다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long orderItemId = order.items().getFirst().orderItemId();
        billing.setSettledHp(PRICE_HP - 1_000L);

        OrderItemResponse response = lectureOrderFacade.cancel(BUYER_ID, order.orderId(), orderItemId);

        // 결과 금액이 작업 금액과 다르면 불변식 위반이라 DEAD다. 완료 상태로 바꾸지 않는다 (D43, D57)
        assertThat(response.status()).isEqualTo(OrderItemStatus.CANCEL_PENDING);
        assertThat(response.processingStatus()).isEqualTo(ProcessingStatus.ACTION_REQUIRED);
    }

    @Test
    void 구매_취소_처리_중인_항목은_다시_구매_취소할_수_없다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long orderItemId = order.items().getFirst().orderItemId();
        billing.respond(command -> {
            throw BillingCallExceptions.of(BillingCallFailureKind.UNKNOWN_RESULT, 0, null);
        });
        lectureOrderFacade.cancel(BUYER_ID, order.orderId(), orderItemId);

        assertErrorCode(() -> lectureOrderFacade.cancel(BUYER_ID, order.orderId(), orderItemId),
                LectureErrorCode.ORDER_ITEM_NOT_PURCHASED);
    }

    @Test
    void 자동_구매_확정_시각이_지난_항목은_구매_취소할_수_없다() {
        LectureOrder expired = lectureOrderRepository.save(LectureOrderFixture.order(BUYER_ID,
                List.of(saveOnSale("OBS 방송 세팅")), LocalDateTime.now(clock).minusDays(8)));
        Long orderItemId = expired.getItems().getFirst().getId();

        assertErrorCode(() -> lectureOrderFacade.cancel(BUYER_ID, expired.getId(), orderItemId),
                LectureErrorCode.CANCEL_PERIOD_EXPIRED);

        assertThat(billing.calls()).isEmpty();
        assertThat(itemStatus(expired.getId())).isEqualTo(OrderItemStatus.PURCHASED);
    }

    @Test
    void 남의_주문은_구매_취소할_수_없다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long orderItemId = order.items().getFirst().orderItemId();
        billing.reset();

        assertErrorCode(() -> lectureOrderFacade.cancel(OTHER_BUYER_ID, order.orderId(), orderItemId),
                LectureErrorCode.ORDER_NOT_FOUND);
        assertThat(billing.calls()).isEmpty();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 관리자는_구매_취소할_수_없다() {
        LectureOrder saved = lectureOrderRepository.save(LectureOrderFixture.order(BUYER_ID,
                List.of(saveOnSale("OBS 방송 세팅")), LocalDateTime.now(clock)));

        assertThatThrownBy(() -> lectureOrderFacade.cancel(BUYER_ID, saved.getId(),
                saved.getItems().getFirst().getId()))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThat(billing.calls()).isEmpty();
    }

    /** 실행할 때가 된 HP 작업을 배치로 실행한다. 재시도를 기다리거나 선점이 남은 작업은 시각을 당긴다 */
    private void runDueTasks() {
        jdbcTemplate.update("update lectures.lecture_hp_task set next_attempt_at = now() - interval '10 minutes', "
                + "lease_until = case when lease_until is null then null else now() - interval '10 minutes' end "
                + "where status in ('PENDING', 'PROCESSING')");
        lectureHpTaskBatchUseCase.runDue();
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (BrokenBarrierException | TimeoutException e) {
            // 한쪽만 들어오면(직렬로 실행된 경우) 기다리지 않고 진행한다
        }
    }

    private OrderResponse order(Lecture... lectures) {
        return lectureOrderFacade.createOrder(BUYER_ID, List.of(lectures).stream().map(Lecture::getId).toList());
    }

    private OrderItemStatus itemStatus(Long orderId) {
        return lectureOrderFacade.getOrder(BUYER_ID, orderId).items().getFirst().status();
    }

    private Lecture saveOnSale(String title) {
        Lecture lecture = Lecture.register(SELLER_ID, title, DESCRIPTION, PRICE_HP, null, contentSpecs(), NOW);
        lecture.approve(ADMIN_ID, NOW);
        return lectureRepository.save(lecture);
    }

    private static void assertErrorCode(Runnable action, LectureErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }
}

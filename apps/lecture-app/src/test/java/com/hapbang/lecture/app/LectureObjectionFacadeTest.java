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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.app.dto.OrderItemResponse;
import com.hapbang.lecture.app.dto.OrderResponse;
import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.LectureHpTaskStatus;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.LectureOrderStatus;
import com.hapbang.lecture.domain.ObjectionStatus;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.domain.OrderItemStatus;
import com.hapbang.lecture.fake.FakeBillingApiClient;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.lecture.out.LectureHpTaskRepository;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.shared.billing.domain.BillingFailureCode;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.out.BillingCallResult;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 이의 제기 Facade(LectureObjectionFacade)를 직접 호출해 DB·HP 반환과 지급 예정 취소(Fake billing)·트랜잭션 참여까지 확인한다.
 * 상태 규칙과 기한 경계는 LectureOrderObjectionTest가 맡는다.
 */
@IntegrationTest
@WithMockUser(roles = "USER")
class LectureObjectionFacadeTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final Long INQUIRY_ID = 500L;
    private static final String REASON = "강의 소개와 영상 내용이 다릅니다.";

    @Autowired
    private LectureObjectionFacade lectureObjectionFacade;

    @Autowired
    private LectureOrderFacade lectureOrderFacade;

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private FakeBillingApiClient billing;

    @Autowired
    private LectureHpTaskBatchUseCase lectureHpTaskBatchUseCase;

    @Autowired
    private LectureHpTaskExecutor lectureHpTaskExecutor;

    @Autowired
    private LectureHpTaskRepository lectureHpTaskRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    @Qualifier(LectureConfig.CLOCK)
    private Clock clock;

    @BeforeEach
    void setUp() {
        billing.reset();
        billing.setSettledHp(PRICE_HP);
    }

    @Test
    void 접수하면_FILED와_문의ID와_사유가_저장되고_구매_취소는_거부된다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long orderItemId = order.items().getFirst().orderItemId();

        lectureObjectionFacade.fileObjection(orderItemId, BUYER_ID, INQUIRY_ID, REASON);

        OrderItem item = item(orderItemId);
        assertThat(item.getObjectionStatus()).isEqualTo(ObjectionStatus.FILED);
        assertThat(item.getObjectionInquiryId()).isEqualTo(INQUIRY_ID);
        assertThat(item.getObjectionReason()).isEqualTo(REASON);
        assertThat(item.getObjectionFiledAt()).isNotNull();
        assertThatThrownBy(() -> lectureOrderFacade.cancel(BUYER_ID, order.orderId(), orderItemId))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.OBJECTION_FILED);
        assertThat(releases()).isEmpty();
    }

    @Test
    void 없는_항목에는_이의_제기할_수_없다() {
        assertRefused(() -> lectureObjectionFacade.fileObjection(999_999L, BUYER_ID, INQUIRY_ID, REASON),
                LectureErrorCode.OBJECTION_NOT_BUYER);
    }

    @Test
    void 지급_예정_시각이_지난_항목은_이의_제기할_수_없다() {
        LectureOrder expired = lectureOrderRepository.save(LectureOrderFixture.order(BUYER_ID,
                List.of(saveOnSale("OBS 방송 세팅")), LocalDateTime.now(clock).minusDays(31)));
        Long orderItemId = expired.getItems().getFirst().getId();

        assertRefused(() -> lectureObjectionFacade.fileObjection(orderItemId, BUYER_ID, INQUIRY_ID, REASON),
                LectureErrorCode.OBJECTION_PERIOD_EXPIRED);
        assertThat(item(orderItemId).getObjectionStatus()).isEqualTo(ObjectionStatus.NONE);
    }

    @Test
    void 인정하면_반환_처리_중이_되고_HP_작업_배치가_반환하면_반환이_된다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"), saveOnSale("마이크 고르기"));
        Long orderItemId = order.items().getFirst().orderItemId();
        lectureObjectionFacade.fileObjection(orderItemId, BUYER_ID, INQUIRY_ID, REASON);

        lectureObjectionFacade.resolveObjection(orderItemId, INQUIRY_ID, true, ADMIN_ID);

        // 판단 요청 안에서는 지갑을 부르지 않는다 (D70 (1))
        OrderItem accepted = item(orderItemId);
        assertThat(accepted.getStatus()).isEqualTo(OrderItemStatus.RETURN_PENDING);
        assertThat(accepted.getReturnedAt()).isNull();
        assertThat(accepted.getObjectionStatus()).isEqualTo(ObjectionStatus.ACCEPTED);
        assertThat(accepted.getObjectionResolvedAt()).isNotNull();
        assertThat(accepted.getObjectionResolvedByUserId()).isEqualTo(ADMIN_ID);
        assertThat(releases()).isEmpty();

        lectureHpTaskBatchUseCase.runDue();

        assertThat(item(orderItemId)).satisfies(item -> {
            assertThat(item.getStatus()).isEqualTo(OrderItemStatus.RETURNED);
            assertThat(item.getReturnedAt()).isNotNull();
        });
        assertThat(billing.calls()).filteredOn(call -> call.command() instanceof ReleaseCommand).singleElement()
                .satisfies(call -> {
                    assertThat(call.transactionActive()).isFalse();
                    ReleaseCommand release = (ReleaseCommand) call.command();
                    assertThat(release.userId()).isEqualTo(BUYER_ID);
                    assertThat(release.referenceType()).isEqualTo(BillingReferenceType.LECTURE_ORDER_ITEM);
                    assertThat(release.referenceId()).isEqualTo(orderItemId);
                    assertThat(release.releaseRequestId()).isEqualTo("LECTURE-RETURN-" + orderItemId);
                    assertThat(release.isFull()).isTrue();
                });
        assertThat(lectureOrderFacade.getOrder(BUYER_ID, order.orderId()).status())
                .isEqualTo(LectureOrderStatus.PAID);
    }

    @Test
    void 기각하면_REJECTED로_저장되고_HP는_움직이지_않는다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long orderItemId = order.items().getFirst().orderItemId();
        lectureObjectionFacade.fileObjection(orderItemId, BUYER_ID, INQUIRY_ID, REASON);

        lectureObjectionFacade.resolveObjection(orderItemId, INQUIRY_ID, false, ADMIN_ID);

        OrderItem item = item(orderItemId);
        assertThat(item.getObjectionStatus()).isEqualTo(ObjectionStatus.REJECTED);
        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.PURCHASED);
        assertThat(item.getObjectionResolvedByUserId()).isEqualTo(ADMIN_ID);
        assertThat(releases()).isEmpty();
    }

    @Test
    void 인정_반환액이_항목_가격과_다르면_반환_처리_중에_멈추고_관리자_확인이_필요하다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long orderItemId = order.items().getFirst().orderItemId();
        lectureObjectionFacade.fileObjection(orderItemId, BUYER_ID, INQUIRY_ID, REASON);
        lectureObjectionFacade.resolveObjection(orderItemId, INQUIRY_ID, true, ADMIN_ID);
        billing.setSettledHp(PRICE_HP - 1_000L);

        lectureHpTaskBatchUseCase.runDue();

        // 결과 금액이 작업 금액과 다르면 불변식 위반이라 DEAD다. 완료 상태로 바꾸지 않는다 (D43, D57)
        assertThat(item(orderItemId).getStatus()).isEqualTo(OrderItemStatus.RETURN_PENDING);
        assertThat(lectureHpTaskRepository.findByRequestId("LECTURE-RETURN-" + orderItemId))
                .map(LectureHpTask::getStatus).hasValue(LectureHpTaskStatus.DEAD);
    }

    @Test
    void 지갑이_반환을_거부하면_반환_처리_중에_멈춘다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long orderItemId = order.items().getFirst().orderItemId();
        lectureObjectionFacade.fileObjection(orderItemId, BUYER_ID, INQUIRY_ID, REASON);
        lectureObjectionFacade.resolveObjection(orderItemId, INQUIRY_ID, true, ADMIN_ID);
        billing.respond(command -> new BillingCallResult.Failed<>(BillingFailureCode.HOLD_NOT_FOUND,
                "HP 보류를 찾을 수 없습니다.", 404, "HOLD_NOT_FOUND"));

        lectureHpTaskBatchUseCase.runDue();

        assertThat(item(orderItemId).getStatus()).isEqualTo(OrderItemStatus.RETURN_PENDING);
        assertThat(lectureOrderFacade.getOrder(BUYER_ID, order.orderId()).status())
                .isEqualTo(LectureOrderStatus.PAID);
    }

    @Test
    void 같은_판단을_두_번_호출하면_두_번째는_거부되고_HP는_한_번만_반환된다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long orderItemId = order.items().getFirst().orderItemId();
        lectureObjectionFacade.fileObjection(orderItemId, BUYER_ID, INQUIRY_ID, REASON);
        lectureObjectionFacade.resolveObjection(orderItemId, INQUIRY_ID, true, ADMIN_ID);

        assertRefused(() -> lectureObjectionFacade.resolveObjection(orderItemId, INQUIRY_ID, true, ADMIN_ID),
                LectureErrorCode.OBJECTION_NOT_FILED);
        lectureHpTaskBatchUseCase.runDue();

        assertThat(releases()).hasSize(1);
    }

    @Test
    void 없는_항목과_다른_문의로는_판단할_수_없다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long orderItemId = order.items().getFirst().orderItemId();
        lectureObjectionFacade.fileObjection(orderItemId, BUYER_ID, INQUIRY_ID, REASON);

        assertRefused(() -> lectureObjectionFacade.resolveObjection(999_999L, INQUIRY_ID, true, ADMIN_ID),
                LectureErrorCode.OBJECTION_NOT_FILED);
        assertRefused(() -> lectureObjectionFacade.resolveObjection(999_999L, INQUIRY_ID, false, ADMIN_ID),
                LectureErrorCode.OBJECTION_NOT_FILED);
        assertRefused(() -> lectureObjectionFacade.resolveObjection(orderItemId, 501L, false, ADMIN_ID),
                LectureErrorCode.OBJECTION_INQUIRY_MISMATCH);
        assertRefused(() -> lectureObjectionFacade.resolveObjection(orderItemId, 501L, true, ADMIN_ID),
                LectureErrorCode.OBJECTION_INQUIRY_MISMATCH);
        assertFiledAndPurchased(orderItemId);
    }

    @Test
    void 호출한_쪽_트랜잭션이_롤백되면_접수도_롤백된다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long orderItemId = order.items().getFirst().orderItemId();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            lectureObjectionFacade.fileObjection(orderItemId, BUYER_ID, INQUIRY_ID, REASON);
            throw new IllegalStateException("고객센터 문의 저장 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(item(orderItemId).getObjectionStatus()).isEqualTo(ObjectionStatus.NONE);
    }

    @Test
    void 접수_불가_예외를_잡고_삼키면_호출한_쪽_트랜잭션은_커밋되지_않는다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"), saveOnSale("마이크 고르기"));
        Long firstItemId = order.items().getFirst().orderItemId();
        Long secondItemId = order.items().getLast().orderItemId();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            lectureObjectionFacade.fileObjection(firstItemId, BUYER_ID, INQUIRY_ID, REASON);
            try {
                lectureObjectionFacade.fileObjection(secondItemId, 4L, 501L, REASON);
            } catch (LectureException ignored) {
                // 계약 위반: 다시 던지지 않는다
            }
        })).isInstanceOf(UnexpectedRollbackException.class);

        assertThat(item(firstItemId).getObjectionStatus()).isEqualTo(ObjectionStatus.NONE);
    }

    @Test
    void 같은_주문의_마지막_두_항목의_반환이_동시에_끝나도_주문이_취소된다() throws Exception {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"), saveOnSale("마이크 고르기"));
        List<Long> itemIds = order.items().stream().map(OrderItemResponse::orderItemId).toList();
        for (Long itemId : itemIds) {
            lectureObjectionFacade.fileObjection(itemId, BUYER_ID, INQUIRY_ID + itemId, REASON);
            lectureObjectionFacade.resolveObjection(itemId, INQUIRY_ID + itemId, true, ADMIN_ID);
        }
        // 두 반환이 모두 지갑 호출에 들어갈 때까지 기다리게 해, 결과 기록이 주문을 잠그지 않으면 서로의 반환을 보지 못하게 한다
        CyclicBarrier bothReleasing = new CyclicBarrier(2);
        billing.setDuringCall(() -> await(bothReleasing));

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<?>> futures = itemIds.stream()
                    .map(itemId -> lectureHpTaskRepository.findByRequestId("LECTURE-RETURN-" + itemId)
                            .orElseThrow().getId())
                    .<Future<?>>map(taskId -> executor.submit(() -> lectureHpTaskExecutor.execute(taskId)))
                    .toList();
            for (Future<?> future : futures) {
                future.get();
            }
        }

        OrderResponse saved = lectureOrderFacade.getOrder(BUYER_ID, order.orderId());
        assertThat(saved.items()).extracting(OrderItemResponse::status).containsOnly(OrderItemStatus.RETURNED);
        assertThat(saved.status()).isEqualTo(LectureOrderStatus.CANCELED);
        assertThat(releases()).hasSize(2);
    }

    @Test
    void 철회하면_NONE으로_저장되고_구매_취소하면_보류_HP를_반환한다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long orderItemId = order.items().getFirst().orderItemId();
        lectureObjectionFacade.fileObjection(orderItemId, BUYER_ID, INQUIRY_ID, REASON);

        lectureObjectionFacade.withdrawObjection(orderItemId, BUYER_ID, INQUIRY_ID);

        OrderItem item = item(orderItemId);
        assertThat(item.getObjectionStatus()).isEqualTo(ObjectionStatus.NONE);
        assertThat(item.getObjectionInquiryId()).isNull();
        assertThat(item.getObjectionReason()).isNull();
        assertThat(item.getObjectionFiledAt()).isNull();
        assertThat(releases()).isEmpty();

        lectureOrderFacade.cancel(BUYER_ID, order.orderId(), orderItemId);

        assertThat(item(orderItemId).getStatus()).isEqualTo(OrderItemStatus.CANCELED);
        assertThat(releases()).singleElement()
                .extracting(ReleaseCommand::referenceId).isEqualTo(orderItemId);
    }

    @Test
    void 없는_항목은_철회할_수_없다() {
        assertRefused(() -> lectureObjectionFacade.withdrawObjection(999_999L, BUYER_ID, INQUIRY_ID),
                LectureErrorCode.OBJECTION_NOT_BUYER);
    }

    @Test
    void 호출한_쪽_트랜잭션이_롤백되면_철회도_롤백된다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long orderItemId = order.items().getFirst().orderItemId();
        lectureObjectionFacade.fileObjection(orderItemId, BUYER_ID, INQUIRY_ID, REASON);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            lectureObjectionFacade.withdrawObjection(orderItemId, BUYER_ID, INQUIRY_ID);
            throw new IllegalStateException("고객센터 문의 삭제 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertFiledAndPurchased(orderItemId);
        assertThat(item(orderItemId).getObjectionInquiryId()).isEqualTo(INQUIRY_ID);
    }

    private OrderResponse order(Lecture... lectures) {
        return lectureOrderFacade.createOrder(BUYER_ID, List.of(lectures).stream().map(Lecture::getId).toList());
    }

    private OrderItem item(Long orderItemId) {
        return lectureOrderRepository.findItemOfBuyer(orderItemId, BUYER_ID).orElseThrow();
    }

    private void assertFiledAndPurchased(Long orderItemId) {
        OrderItem item = item(orderItemId);
        assertThat(item.getObjectionStatus()).isEqualTo(ObjectionStatus.FILED);
        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.PURCHASED);
        assertThat(item.getReturnedAt()).isNull();
    }

    private Lecture saveOnSale(String title) {
        Lecture lecture = Lecture.register(SELLER_ID, title, DESCRIPTION, PRICE_HP, null, contentSpecs(), NOW);
        lecture.approve(ADMIN_ID, NOW);
        return lectureRepository.save(lecture);
    }

    private List<ReleaseCommand> releases() {
        return billing.calls().stream()
                .map(FakeBillingApiClient.Call::command)
                .filter(ReleaseCommand.class::isInstance)
                .map(ReleaseCommand.class::cast)
                .toList();
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

    private static void assertRefused(Runnable action, LectureErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }
}

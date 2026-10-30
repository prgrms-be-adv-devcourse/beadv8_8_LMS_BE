package com.hapbang.lecture.app;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.LectureHpTaskPurpose;
import com.hapbang.lecture.domain.LectureHpTaskStatus;
import com.hapbang.lecture.domain.LectureOrder;
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
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.out.BillingCallResult;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 지급 (D59 (5)). 01:00 지급 배치는 지급 처리 중과 PAYOUT 작업만 만들고, 지갑 호출과 재시도는 HP 작업 배치가 한다.
 */
@IntegrationTest
class OrderPayoutUseCaseTest {

    private static final Long BUYER_ID = OTHER_USER_ID;

    @Autowired
    private OrderPayoutUseCase orderPayoutUseCase;

    @Autowired
    private LectureHpTaskBatchUseCase lectureHpTaskBatchUseCase;

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private LectureHpTaskRepository lectureHpTaskRepository;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private FakeBillingApiClient billing;

    // 한 항목의 처리 실패를 만들기 위해 항목별 주문 조회에서 예외를 던진다
    @MockitoSpyBean
    private LectureOrderSupport lectureOrderSupport;

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
        billing.setSettledHp(PRICE_HP);
    }

    @Test
    void 지급_배치는_지갑을_부르지_않고_지급_처리_중과_PAYOUT_작업만_만든다() {
        LocalDateTime overdue = now().minusDays(32);
        LectureOrder due = saveConfirmed(overdue);
        LectureOrder notDue = saveConfirmed(now().minusDays(20));
        LectureOrder purchased = savePurchased(overdue);

        orderPayoutUseCase.payOutDueItems();

        Long dueItemId = itemId(due);
        assertThat(billing.calls()).isEmpty();
        assertThat(items(due)).extracting(OrderItem::getStatus, OrderItem::getPaidOutAt)
                .containsExactly(tuple(OrderItemStatus.PAYOUT_PENDING, null));
        assertThat(lectureHpTaskRepository.findByRequestId("LECTURE-PAYOUT-" + dueItemId)).hasValueSatisfying(task -> {
            assertThat(task.getPurpose()).isEqualTo(LectureHpTaskPurpose.PAYOUT);
            assertThat(task.getStatus()).isEqualTo(LectureHpTaskStatus.PENDING);
            assertThat(task.getReceiverUserId()).isEqualTo(SELLER_ID);
            assertThat(task.getAmountHp()).isEqualTo(PRICE_HP);
        });
        assertThat(items(notDue)).extracting(OrderItem::getStatus).containsExactly(OrderItemStatus.CONFIRMED);
        assertThat(items(purchased)).extracting(OrderItem::getStatus).containsExactly(OrderItemStatus.PURCHASED);
    }

    @Test
    void HP_작업_배치가_지급하면_지급_완료이고_지급_시각을_남긴다() {
        LectureOrder due = saveConfirmed(now().minusDays(32));
        orderPayoutUseCase.payOutDueItems();
        LocalDateTime before = nowInDbPrecision();

        lectureHpTaskBatchUseCase.runDue();

        Long dueItemId = itemId(due);
        assertThat(billing.calls()).singleElement().satisfies(call -> {
            assertThat(call.transactionActive()).isFalse();
            assertThat(call.command()).isEqualTo(PayoutCommand.full(BUYER_ID, BillingReferenceType.LECTURE_ORDER_ITEM,
                    dueItemId, "LECTURE-PAYOUT-" + dueItemId, SELLER_ID, SettlementSourceType.LECTURE_ORDER_ITEM,
                    dueItemId));
        });
        assertThat(items(due)).singleElement().satisfies(item -> {
            assertThat(item.getStatus()).isEqualTo(OrderItemStatus.PAID_OUT);
            assertThat(item.getPaidOutAt()).isAfterOrEqualTo(before);
        });
    }

    @Test
    void 지급_처리_중인_항목은_다음_지급_배치가_다시_고르지_않는다() {
        saveConfirmed(now().minusDays(32));

        orderPayoutUseCase.payOutDueItems();
        orderPayoutUseCase.payOutDueItems();

        assertThat(lectureHpTaskRepository.count()).isEqualTo(1);
    }

    @Test
    void 이의_제기_접수_중인_항목은_지급을_시작하지_않는다() {
        LectureOrder filed = saveConfirmed(now().minusDays(32));
        // 지급 예정 시각 전에 접수된 이의 제기가 아직 판단되지 않은 상태를 만든다
        jdbcTemplate.update("update lectures.order_item set objection_status = ? where id = ?",
                ObjectionStatus.FILED.name(), itemId(filed));

        orderPayoutUseCase.payOutDueItems();

        assertThat(items(filed)).extracting(OrderItem::getStatus).containsExactly(OrderItemStatus.CONFIRMED);
        assertThat(lectureHpTaskRepository.count()).isZero();
    }

    @Test
    void 지급이_확정_거절되면_DEAD이고_항목은_지급_처리_중에_남는다() {
        LectureOrder order = saveConfirmed(now().minusDays(32));
        orderPayoutUseCase.payOutDueItems();
        billing.respond(command -> new BillingCallResult.Failed<>(BillingFailureCode.PAYOUT_ALREADY_SETTLED,
                "이미 지급 예정 건이 있습니다.", 409, "PAYOUT_ALREADY_SETTLED"));

        lectureHpTaskBatchUseCase.runDue();

        // 완료 상태는 HP가 실제로 움직인 뒤에만 기록한다. 관리자가 원인을 확인하고 HP 작업을 다시 시도한다 (D57, D70 (5))
        assertThat(items(order)).extracting(OrderItem::getStatus).containsExactly(OrderItemStatus.PAYOUT_PENDING);
        assertThat(lectureHpTaskRepository.findByRequestId("LECTURE-PAYOUT-" + itemId(order)))
                .map(LectureHpTask::getStatus).hasValue(LectureHpTaskStatus.DEAD);
    }

    @Test
    void 지급_결과를_기록하지_못해도_같은_요청_ID로_다시_지급해_한_번만_지급_완료가_된다() {
        LectureOrder order = saveConfirmed(now().minusDays(32));
        orderPayoutUseCase.payOutDueItems();
        // 지급하는 동안 다른 실행이 선점을 가져가, 첫 결과는 늦은 결과로 버려진다
        billing.setDuringCall(() -> jdbcTemplate.update("""
                update lectures.lecture_hp_task set claim_token = gen_random_uuid()
                where purpose = 'PAYOUT' and status = 'PROCESSING'"""));
        lectureHpTaskBatchUseCase.runDue();
        billing.setDuringCall(() -> {
        });
        assertThat(items(order)).extracting(OrderItem::getStatus).containsExactly(OrderItemStatus.PAYOUT_PENDING);

        expireLeases();
        lectureHpTaskBatchUseCase.runDue();

        assertThat(items(order)).extracting(OrderItem::getStatus).containsExactly(OrderItemStatus.PAID_OUT);
        assertThat(billing.calls()).hasSize(2)
                .extracting(call -> ((PayoutCommand) call.command()).payoutRequestId())
                .containsOnly("LECTURE-PAYOUT-" + itemId(order));
    }

    @Test
    void 한_항목_처리가_실패해도_다음_항목의_지급을_시작하고_실패한_항목은_다음_실행에서_고른다() {
        LectureOrder failing = saveConfirmed(now().minusDays(33));
        LectureOrder next = saveConfirmed(now().minusDays(32));
        doThrow(new IllegalStateException("처리 실패")).when(lectureOrderSupport)
                .findOrderByItemId(eq(itemId(failing)));

        assertThatCode(() -> orderPayoutUseCase.payOutDueItems()).doesNotThrowAnyException();

        assertThat(items(failing)).extracting(OrderItem::getStatus).containsExactly(OrderItemStatus.CONFIRMED);
        assertThat(items(next)).extracting(OrderItem::getStatus).containsExactly(OrderItemStatus.PAYOUT_PENDING);
        reset(lectureOrderSupport);

        orderPayoutUseCase.payOutDueItems();

        assertThat(items(failing)).extracting(OrderItem::getStatus).containsExactly(OrderItemStatus.PAYOUT_PENDING);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /**
     * 저장한 시각(TIMESTAMP(6))과 비교할 기준. 저장하면 마이크로초 아래가 잘리므로, 결과 기록이 이 직후 같은 마이크로초 안에서
     * 읽은 시각도 기준 이후로 보도록 기준도 마이크로초로 맞춘다(마이크로초보다 정밀한 시계를 쓰는 Windows·Linux에서 간헐 실패, #312)
     */
    private LocalDateTime nowInDbPrecision() {
        return now().truncatedTo(ChronoUnit.MICROS);
    }

    /** 선점이 만료된 작업을 배치가 다시 실행하게 한다 */
    private void expireLeases() {
        jdbcTemplate.update("update lectures.lecture_hp_task set lease_until = now() - interval '10 minutes', "
                + "next_attempt_at = now() - interval '10 minutes' where status in ('PENDING', 'PROCESSING')");
    }

    /** {@code purchasedAt}에 산 주문 (구매 상태) */
    private LectureOrder savePurchased(LocalDateTime purchasedAt) {
        Lecture lecture = lectureRepository.save(onSaleLecture());
        return lectureOrderRepository.save(LectureOrderFixture.order(BUYER_ID, List.of(lecture), purchasedAt));
    }

    /** {@code purchasedAt}에 사서 바로 구매 확정한 주문 */
    private LectureOrder saveConfirmed(LocalDateTime purchasedAt) {
        LectureOrder order = savePurchased(purchasedAt);
        inTransaction(order, saved -> saved.confirmItem(itemId(order), purchasedAt));
        return order;
    }

    private static Long itemId(LectureOrder order) {
        return order.getItems().getFirst().getId();
    }

    // 주문은 항목 변경을 병합(merge)으로 전파하지 않으므로 상태 변경은 트랜잭션 안에서 한다
    private void inTransaction(LectureOrder order, Consumer<LectureOrder> change) {
        transactionTemplate.executeWithoutResult(status ->
                change.accept(lectureOrderRepository.findById(order.getId()).orElseThrow()));
    }

    private List<OrderItem> items(LectureOrder order) {
        return transactionTemplate.execute(status ->
                lectureOrderRepository.findById(order.getId()).orElseThrow().getItems());
    }
}

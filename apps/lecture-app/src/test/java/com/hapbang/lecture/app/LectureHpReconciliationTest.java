package com.hapbang.lecture.app;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.config.LectureHpReconciliationProperties;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.domain.LectureHpReconciliationCursor;
import com.hapbang.lecture.domain.LectureHpReconciliationMismatch;
import com.hapbang.lecture.domain.LectureHpReconciliationRule;
import com.hapbang.lecture.domain.LectureHpReconciliationRun;
import com.hapbang.lecture.domain.LectureHpReconciliationStatus;
import com.hapbang.lecture.domain.LectureHpReconciliationTrigger;
import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.LectureHpTaskOutcome;
import com.hapbang.lecture.domain.LectureHpTaskPurpose;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.OrderItemCoupon;
import com.hapbang.lecture.domain.OrderItemStatus;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.lecture.out.LectureHpReconciliationCursorRepository;
import com.hapbang.lecture.out.LectureHpReconciliationMismatchRepository;
import com.hapbang.lecture.out.LectureHpReconciliationRunRepository;
import com.hapbang.lecture.out.LectureHpTaskRepository;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.lecture.out.OrderItemCouponRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldQueryCommand;
import com.hapbang.shared.billing.dto.HoldStatePageDto;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.out.BillingCallResult;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.billing.RealBillingTestFixture;

/**
 * 서비스 간 HP 대사 (#580, D74). 주문 항목은 fixture로 상태를 만들고 HP 작업을 직접 남기며, 지갑 기록은 별도 billing 앱의 실제 HTTP 보류·반환·지급으로 만든다.
 * <ul>
 *     <li>항목 가격 10,000 HP. 작업 변경 시각을 3시간 전으로 두면 진행 중 유예(2시간)를 지나 비교 대상이 된다</li>
 *     <li>규칙 하나하나는 LectureHpReconciliationComparerTest가, HTTP 응답은 LectureHpReconciliationAdminApiTest가 맡는다</li>
 * </ul>
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class LectureHpReconciliationTest {

    private static final Long BUYER_ID = OTHER_USER_ID;

    @Autowired
    private LectureHpReconciliationUseCase useCase;

    @Autowired
    private LectureHpReconciliationSupport support;

    @Autowired
    private LectureHpReconciliationMetrics metrics;

    @Autowired
    private LectureHpReconciliationMetricsRestorer restorer;

    @Autowired
    private LectureHpReconciliationRunRepository runRepository;

    @Autowired
    private LectureHpReconciliationMismatchRepository mismatchRepository;

    @Autowired
    private LectureHpReconciliationCursorRepository cursorRepository;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private LectureHpTaskRepository lectureHpTaskRepository;

    @Autowired
    private OrderItemCouponRepository orderItemCouponRepository;

    @Autowired
    private RealBillingTestFixture billing;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier(LectureConfig.CLOCK)
    private Clock clock;

    /** 진행 중 유예(2시간)를 지난 변경 시각 */
    private LocalDateTime old;

    @BeforeEach
    void setUp() {
        billing.reset();
        billing.wallet(BUYER_ID, 1_000_000L, 0L);
        billing.wallet(SELLER_ID, 0L, 0L);
        old = LocalDateTime.now(clock).minusHours(3);
    }

    @Test
    void 정상_데이터는_어긋나지_않고_비교한_수만_센다() {
        Item purchased = item(OrderItemStatus.PURCHASED, old);
        Item canceled = item(OrderItemStatus.CANCELED, old);
        Item paidOut = item(OrderItemStatus.PAID_OUT, old);
        Item holdFailed = item(OrderItemStatus.HOLD_FAILED, old);
        seedHold(purchased.id(), PRICE_HP);
        seedRelease(canceled.id());
        seedPayout(paidOut.id());

        LectureHpReconciliationRun run = useCase.runScheduled();

        assertThat(run.getStatus()).isEqualTo(LectureHpReconciliationStatus.COMPLETED);
        assertThat(run.getTriggerType()).isEqualTo(LectureHpReconciliationTrigger.SCHEDULED);
        assertThat(run.getCheckedCount()).isEqualTo(4);
        assertThat(run.getMismatchCount()).isZero();
        assertThat(mismatchRepository.findByRunIdOrderByIdAsc(run.getId())).isEmpty();
        assertThat(queriedIds()).containsExactlyInAnyOrder(purchased.id(), canceled.id(), paidOut.id(),
                holdFailed.id());
        assertThat(metrics.mismatches(LectureHpReconciliationRule.HOLD_BALANCE_MISMATCH)).isZero();
    }

    @Test
    void 플랫폼_쿠폰_예약이_거절돼_generation이_없어도_결제실패를_대사할_수_있다() {
        Long itemId = transactionTemplate.execute(tx -> {
            Lecture lecture = lectureRepository.save(onSaleLecture());
            LectureOrder order = lectureOrderRepository.saveAndFlush(
                    LectureOrderFixture.pending(BUYER_ID, List.of(lecture), old));
            var item = order.getItems().getFirst();
            order.applyItemDiscount(lecture.getId(), PRICE_HP, PRICE_HP, 0L);
            orderItemCouponRepository.save(OrderItemCoupon.create(item.getId(), 901L, 42L, "PLATFORM", 1,
                    PRICE_HP, PRICE_HP, PRICE_HP, 0L, 2L, 1));
            order.markHoldFailed();
            lectureHpTaskRepository.save(LectureHpTask.create(LectureHpTaskPurpose.ORDER_HOLD,
                    "LECTURE-ORDER-HOLD-" + item.getId(), order.getId(), item.getId(), BUYER_ID, null, 0L, old))
                    .complete(LectureHpTaskOutcome.REJECTED, old);
            return item.getId();
        });

        assertThat(support.loadTargets(List.of(itemId)).get(itemId).promotions()).isEmpty();
        LectureHpReconciliationRun run = useCase.runScheduled();

        assertThat(run.getStatus()).isEqualTo(LectureHpReconciliationStatus.COMPLETED);
        assertThat(run.getCheckedCount()).isEqualTo(1);
        assertThat(run.getMismatchCount()).isZero();
    }

    @Test
    void 진행_중인_항목은_비교하지_않는다() {
        // 결제 처리 중, 대기 중인 반환 작업, 유예 시간 안에 끝난 취소. 지갑 기록은 모두 어긋나 있다
        Item holdPending = item(OrderItemStatus.HOLD_PENDING, old);
        Item cancelPending = item(OrderItemStatus.CANCEL_PENDING, old);
        Item recentlyCanceled = item(OrderItemStatus.CANCELED, LocalDateTime.now(clock).minusMinutes(30));
        seedHold(recentlyCanceled.id(), PRICE_HP);

        LectureHpReconciliationRun run = useCase.runScheduled();

        assertThat(run.getStatus()).isEqualTo(LectureHpReconciliationStatus.COMPLETED);
        assertThat(run.getCheckedCount()).isZero();
        assertThat(run.getMismatchCount()).isZero();
        assertThat(queriedIds()).doesNotContain(holdPending.id(), cancelPending.id(), recentlyCanceled.id());
    }

    @Test
    void 일부러_어긋나게_만든_데이터는_규칙마다_잡고_경고_지표를_바꾼다() {
        Item canceledButHeld = item(OrderItemStatus.CANCELED, old);
        Item paidWithoutSettlement = item(OrderItemStatus.PAID_OUT, old);
        Item wrongAmount = item(OrderItemStatus.CONFIRMED, old);
        seedHold(canceledButHeld.id(), PRICE_HP);
        seedRelease(paidWithoutSettlement.id());
        seedHold(wrongAmount.id(), PRICE_HP + 500);
        long walletOnlyId = 987_654L;
        seedHold(walletOnlyId, PRICE_HP);

        LectureHpReconciliationRun run = useCase.runScheduled();

        assertThat(run.getStatus()).isEqualTo(LectureHpReconciliationStatus.COMPLETED);
        List<LectureHpReconciliationMismatch> mismatches = mismatchRepository.findByRunIdOrderByIdAsc(run.getId());
        assertThat(mismatches).extracting(LectureHpReconciliationMismatch::getOrderItemId,
                        LectureHpReconciliationMismatch::getRule)
                .containsExactlyInAnyOrder(
                        tuple(canceledButHeld.id(), LectureHpReconciliationRule.HOLD_BALANCE_MISMATCH),
                        tuple(paidWithoutSettlement.id(), LectureHpReconciliationRule.SETTLEMENT_MISSING),
                        tuple(paidWithoutSettlement.id(), LectureHpReconciliationRule.HOLD_BALANCE_MISMATCH),
                        tuple(wrongAmount.id(), LectureHpReconciliationRule.HOLD_MISMATCH),
                        tuple(wrongAmount.id(), LectureHpReconciliationRule.HOLD_BALANCE_MISMATCH),
                        tuple(walletOnlyId, LectureHpReconciliationRule.WALLET_ONLY_HOLD));
        LectureHpReconciliationMismatch canceled = mismatches.stream()
                .filter(mismatch -> mismatch.getOrderItemId().equals(canceledButHeld.id())).findFirst().orElseThrow();
        assertThat(canceled.getOrderId()).isEqualTo(canceledButHeld.orderId());
        assertThat(canceled.getItemStatus()).isEqualTo(OrderItemStatus.CANCELED);
        assertThat(canceled.getLectureState()).contains("CANCELED");
        assertThat(canceled.getWalletState()).contains("보류 HELD");
        LectureHpReconciliationMismatch walletOnly = mismatches.stream()
                .filter(mismatch -> mismatch.getOrderItemId().equals(walletOnlyId)).findFirst().orElseThrow();
        assertThat(walletOnly.getOrderId()).isNull();
        assertThat(walletOnly.getItemStatus()).isNull();
        assertThat(run.getCheckedCount()).isEqualTo(4);
        assertThat(run.getMismatchCount()).isEqualTo(6);
        assertThat(metrics.mismatches(LectureHpReconciliationRule.HOLD_BALANCE_MISMATCH)).isEqualTo(3);
        assertThat(metrics.mismatches(LectureHpReconciliationRule.SETTLEMENT_MISSING)).isEqualTo(1);
        assertThat(metrics.mismatches(LectureHpReconciliationRule.HOLD_MISMATCH)).isEqualTo(1);
        assertThat(metrics.mismatches(LectureHpReconciliationRule.WALLET_ONLY_HOLD)).isEqualTo(1);
    }

    @Test
    void 어긋난_건은_대사_불일치_표식을_붙여_ERROR_로그로_남긴다(CapturedOutput output) {
        Item canceledButHeld = item(OrderItemStatus.CANCELED, old);
        seedHold(canceledButHeld.id(), PRICE_HP);

        useCase.runScheduled();

        assertThat(output.getAll().lines()
                .filter(line -> line.contains(LectureHpReconciliationUseCase.MISMATCH_LOG_TAG)))
                .singleElement().satisfies(line -> assertThat(line)
                        .contains(" ERROR ", "rule=HOLD_BALANCE_MISMATCH", "orderItemId=" + canceledButHeld.id()));
    }

    @Test
    void DEAD_작업이_있는_항목은_표시만_하고_어긋난_수에_넣지_않는다(CapturedOutput output) {
        Item payoutPending = item(OrderItemStatus.PAYOUT_PENDING, old);
        transactionTemplate.executeWithoutResult(status -> lectureHpTaskRepository.save(task(
                LectureHpTaskPurpose.PAYOUT, payoutPending, SELLER_ID, old)).markDead("PAYOUT_ALREADY_SETTLED", old));

        LectureHpReconciliationRun run = useCase.runScheduled();

        assertThat(run.getStatus()).isEqualTo(LectureHpReconciliationStatus.COMPLETED);
        assertThat(run.getCheckedCount()).isEqualTo(1);
        assertThat(run.getDeadCount()).isEqualTo(1);
        assertThat(run.getMismatchCount()).isZero();
        assertThat(mismatchRepository.findByRunIdOrderByIdAsc(run.getId())).singleElement()
                .satisfies(mismatch -> {
                    assertThat(mismatch.getRule()).isEqualTo(LectureHpReconciliationRule.HP_TASK_DEAD);
                    assertThat(mismatch.getLectureState()).contains("PAYOUT", "PAYOUT_ALREADY_SETTLED");
                });
        assertThat(queriedIds()).isEmpty();
        assertThat(metrics.mismatches(LectureHpReconciliationRule.HP_TASK_DEAD)).isEqualTo(1);
        // 어긋남이 아니므로 대사 불일치 alert 표식 없이 WARN으로만 남긴다. DEAD 자체의 alert는 HP 작업이 남긴다
        assertThat(output.getAll()).doesNotContain(LectureHpReconciliationUseCase.MISMATCH_LOG_TAG);
        assertThat(output.getAll().lines().filter(line -> line.contains("DEAD 작업이 있어 비교하지 않았다")))
                .singleElement().satisfies(line -> assertThat(line)
                        .contains(" WARN ", "orderItemId=" + payoutPending.id()));
    }

    @Test
    void 지갑_목록_조회가_실패하면_미완료로_끝나고_커서를_옮기지_않는다() {
        item(OrderItemStatus.CANCELED, old);
        double incompleteBefore = metrics.runs(LectureHpReconciliationStatus.INCOMPLETE);
        billing.disconnect();

        LectureHpReconciliationRun run;
        try {
            run = useCase.runScheduled();
        } finally {
            billing.reconnect();
        }

        assertThat(run.getStatus()).isEqualTo(LectureHpReconciliationStatus.INCOMPLETE);
        assertThat(run.getFailureReason()).contains("지갑 보류 목록", "RETRYABLE");
        assertThat(run.getCheckedCount()).isZero();
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(cursorRepository.findAll()).isEmpty();
        assertThat(metrics.runs(LectureHpReconciliationStatus.INCOMPLETE)).isEqualTo(incompleteBefore + 1);
    }

    @Test
    void 참조_대상별_조회가_실패하면_미완료로_끝나고_결과를_모르는_건을_어긋남으로_세지_않는다() {
        Item canceledButHeld = item(OrderItemStatus.CANCELED, old);
        seedHold(canceledButHeld.id(), PRICE_HP);
        // 첫 실행은 끝까지 비교해 지표를 남긴다
        useCase.runScheduled();
        assertThat(metrics.mismatches(LectureHpReconciliationRule.HOLD_BALANCE_MISMATCH)).isEqualTo(1);
        LectureHpReconciliationCursor cursor = cursorRepository.findAll().getFirst();
        billing.duringCall(HoldQueryCommand.class::isInstance, billing::disconnect);

        LectureHpReconciliationRun run;
        try {
            run = useCase.runScheduled();
        } finally {
            billing.reconnect();
        }

        assertThat(run.getStatus()).isEqualTo(LectureHpReconciliationStatus.INCOMPLETE);
        assertThat(run.getFailureReason()).contains("지갑 보류 조회 결과를 받지 못함", "RETRYABLE");
        assertThat(run.getCheckedCount()).isZero();
        assertThat(run.getMismatchCount()).isZero();
        assertThat(mismatchRepository.findByRunIdOrderByIdAsc(run.getId())).isEmpty();
        // 커서는 옮기지 않고, 중단된 실행은 지표를 바꾸지 않는다
        LectureHpReconciliationCursor unchanged = cursorRepository.findAll().getFirst();
        assertThat(unchanged.getUpdatedAfter()).isEqualTo(cursor.getUpdatedAfter());
        assertThat(unchanged.getAfterId()).isEqualTo(cursor.getAfterId());
        assertThat(metrics.mismatches(LectureHpReconciliationRule.HOLD_BALANCE_MISMATCH)).isEqualTo(1);
    }

    @Test
    void 회로가_열려_있으면_지갑을_부르지_않고_미완료로_끝난다() {
        billing.openCircuit();

        LectureHpReconciliationRun run = useCase.runScheduled();

        assertThat(run.getStatus()).isEqualTo(LectureHpReconciliationStatus.INCOMPLETE);
        assertThat(run.getFailureReason()).isEqualTo("지갑 회로가 열려 있음");
        assertThat(billing.calls()).isEmpty();
    }

    @Test
    void 지갑_목록은_hasMore가_false일_때까지_이어_읽고_다음_실행은_저장한_커서부터_읽는다() {
        seedHold(987_651L, PRICE_HP);
        seedHold(987_652L, PRICE_HP);
        seedHold(987_653L, PRICE_HP);
        HoldStatePageDto first = page(Instant.EPOCH, 0L, 2);
        HoldStatePageDto last = page(first.nextUpdatedAfter(), first.nextAfterId(), 2);
        assertThat(first.hasMore()).isTrue();
        assertThat(last.hasMore()).isFalse();
        billing.clearCalls();
        LectureHpReconciliationUseCase smallPages = new LectureHpReconciliationUseCase(support, metrics,
                billing.client(), new LectureHpReconciliationProperties(null, null, 2), clock);

        smallPages.runScheduled();

        assertThat(listQueries()).extracting(RealBillingTestFixture.HoldListQuery::updatedAfter,
                        RealBillingTestFixture.HoldListQuery::afterId, RealBillingTestFixture.HoldListQuery::size)
                .containsExactly(tuple(Instant.EPOCH, 0L, 2),
                        tuple(first.nextUpdatedAfter(), first.nextAfterId(), 2));
        LectureHpReconciliationCursor cursor = cursorRepository.findById(BillingReferenceType.LECTURE_ORDER_ITEM)
                .orElseThrow();
        assertThat(cursor.getUpdatedAfter()).isEqualTo(LocalDateTime.ofInstant(last.nextUpdatedAfter(), LectureConfig.ZONE));
        assertThat(cursor.getAfterId()).isEqualTo(last.nextAfterId());

        billing.clearCalls();
        smallPages.runScheduled();

        assertThat(listQueries()).extracting(RealBillingTestFixture.HoldListQuery::updatedAfter,
                        RealBillingTestFixture.HoldListQuery::afterId)
                .containsExactly(tuple(last.nextUpdatedAfter(), last.nextAfterId()));
    }

    @Test
    void 비교_범위_밖의_항목은_지갑에서_바뀐_보류로만_다시_본다() {
        Item stale = item(OrderItemStatus.CANCELED, LocalDateTime.now(clock).minusDays(8));
        seedHold(stale.id(), PRICE_HP);
        // 이전 실행이 이미 읽은 실제 지갑 커서부터 시작한다.
        HoldStatePageDto previous = page(Instant.EPOCH, 0L, 500);
        cursorRepository.save(new LectureHpReconciliationCursor(BillingReferenceType.LECTURE_ORDER_ITEM,
                LocalDateTime.ofInstant(previous.nextUpdatedAfter(), LectureConfig.ZONE), previous.nextAfterId()));
        billing.clearCalls();

        LectureHpReconciliationRun outOfRange = useCase.runScheduled();

        assertThat(outOfRange.getCheckedCount()).isZero();
        assertThat(queriedIds()).isEmpty();

        // 실제 부분 반환으로 보류의 변경 시각이 옮겨지면 강의 비교 범위 밖이어도 다시 읽는다.
        assertThat(billing.client().release(ReleaseCommand.partial(BUYER_ID,
                BillingReferenceType.LECTURE_ORDER_ITEM, stale.id(), "CHANGED-" + stale.id(), 1L)))
                .isInstanceOf(BillingCallResult.Succeeded.class);
        billing.clearCalls();
        LectureHpReconciliationRun walletChanged = useCase.runScheduled();

        assertThat(walletChanged.getCheckedCount()).isEqualTo(1);
        assertThat(mismatchRepository.findByRunIdOrderByIdAsc(walletChanged.getId()))
                .extracting(LectureHpReconciliationMismatch::getRule)
                .containsExactly(LectureHpReconciliationRule.HOLD_BALANCE_MISMATCH);
    }

    @Test
    void 참조_대상별_조회는_조회_건수씩_나눠_부른다() {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Item purchased = item(OrderItemStatus.PURCHASED, old);
            seedHold(purchased.id(), PRICE_HP);
            ids.add(purchased.id());
        }
        LectureHpReconciliationUseCase smallPages = new LectureHpReconciliationUseCase(support, metrics, billing.client(),
                new LectureHpReconciliationProperties(null, null, 2), clock);

        LectureHpReconciliationRun run = smallPages.runScheduled();

        assertThat(run.getCheckedCount()).isEqualTo(3);
        assertThat(holdQueries()).extracting(HoldQueryCommand::referenceIds)
                .containsExactly(ids.subList(0, 2), ids.subList(2, 3));
        assertThat(listQueries()).extracting(RealBillingTestFixture.HoldListQuery::size).containsExactly(2, 2);
        assertThat(billing.calls()).allSatisfy(call -> assertThat(call.transactionActive()).isFalse());
    }

    @Test
    void 비교하는_사이에_바뀐_항목은_이번에_남기지_않고_다음_실행에서_잡는다() {
        Item canceledButHeld = item(OrderItemStatus.CANCELED, old);
        seedHold(canceledButHeld.id(), PRICE_HP);
        billing.duringCall(HoldQueryCommand.class::isInstance, () ->
                jdbcTemplate.update("update lectures.order_item set version = version + 1 where id = ?",
                        canceledButHeld.id()));

        LectureHpReconciliationRun meanwhile = useCase.runScheduled();

        assertThat(meanwhile.getStatus()).isEqualTo(LectureHpReconciliationStatus.COMPLETED);
        assertThat(meanwhile.getCheckedCount()).isZero();
        assertThat(mismatchRepository.findByRunIdOrderByIdAsc(meanwhile.getId())).isEmpty();

        LectureHpReconciliationRun next = useCase.runScheduled();

        assertThat(mismatchRepository.findByRunIdOrderByIdAsc(next.getId()))
                .extracting(LectureHpReconciliationMismatch::getRule)
                .containsExactly(LectureHpReconciliationRule.HOLD_BALANCE_MISMATCH);
    }

    @Test
    void 실행_중이면_배치는_건너뛰고_관리자_실행은_HP_RECONCILIATION_RUNNING이다() {
        AtomicReference<LectureHpReconciliationRun> skipped = new AtomicReference<>();
        AtomicReference<Throwable> manual = new AtomicReference<>();
        AtomicBoolean once = new AtomicBoolean();
        billing.duringNextCall(() -> {
            if (once.compareAndSet(false, true)) {
                skipped.set(useCase.runScheduled());
                manual.set(catchThrowable(() -> useCase.runManual(ADMIN_ID)));
            }
        });

        LectureHpReconciliationRun run = useCase.runScheduled();

        assertThat(run.getStatus()).isEqualTo(LectureHpReconciliationStatus.COMPLETED);
        assertThat(skipped.get()).isNull();
        assertThat(manual.get()).isInstanceOfSatisfying(LectureException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(LectureErrorCode.HP_RECONCILIATION_RUNNING));
        assertThat(runRepository.findAll()).hasSize(1);
    }

    @Test
    void 앱이_멈춰_실행_중으로_남은_실행은_다음_실행이_미완료로_정리한다() {
        Long abandonedId = support.start(LectureHpReconciliationTrigger.SCHEDULED, null, old);

        LectureHpReconciliationRun run = useCase.runManual(ADMIN_ID);

        assertThat(run.getTriggerType()).isEqualTo(LectureHpReconciliationTrigger.MANUAL);
        assertThat(run.getTriggeredBy()).isEqualTo(ADMIN_ID);
        LectureHpReconciliationRun abandoned = runRepository.findById(abandonedId).orElseThrow();
        assertThat(abandoned.getStatus()).isEqualTo(LectureHpReconciliationStatus.INCOMPLETE);
        assertThat(abandoned.getFailureReason()).isEqualTo(LectureHpReconciliationSupport.ABANDONED_REASON);
        assertThat(support.findLatestRun().getId()).isEqualTo(run.getId());
    }

    @Test
    void 끝까지_비교한_실행은_끝난_시각을_지표로_낸다() {
        Item purchased = item(OrderItemStatus.PURCHASED, old);
        seedHold(purchased.id(), PRICE_HP);

        LectureHpReconciliationRun run = useCase.runScheduled();

        assertThat(run.getStatus()).isEqualTo(LectureHpReconciliationStatus.COMPLETED);
        assertThat(metrics.lastCompletedEpochSeconds())
                .isEqualTo(run.getFinishedAt().atZone(LectureConfig.ZONE).toEpochSecond());
    }

    @Test
    void 기동할_때_마지막_완료_실행의_규칙별_건수와_끝난_시각으로_지표를_채운다() {
        LocalDateTime finishedAt = LocalDateTime.now(clock).withNano(0).minusHours(5);
        savedRun(LectureHpReconciliationStatus.COMPLETED, finishedAt.minusDays(1),
                LectureHpReconciliationRule.HOLD_MISMATCH);
        savedRun(LectureHpReconciliationStatus.COMPLETED, finishedAt,
                LectureHpReconciliationRule.HOLD_BALANCE_MISMATCH, LectureHpReconciliationRule.HOLD_BALANCE_MISMATCH,
                LectureHpReconciliationRule.HP_TASK_DEAD);
        // 더 나중에 끝났지만 중단된 실행은 지표를 바꾸지 않는다
        savedRun(LectureHpReconciliationStatus.INCOMPLETE, finishedAt.plusHours(1),
                LectureHpReconciliationRule.SETTLEMENT_MISSING);
        // 앱이 다시 떠서 지표가 비어 있는 상태
        metrics.updateMismatches(Map.of());
        metrics.updateLastCompleted(finishedAt.minusDays(30));

        restorer.restore();

        assertThat(metrics.mismatches(LectureHpReconciliationRule.HOLD_BALANCE_MISMATCH)).isEqualTo(2);
        assertThat(metrics.mismatches(LectureHpReconciliationRule.HP_TASK_DEAD)).isEqualTo(1);
        assertThat(metrics.mismatches(LectureHpReconciliationRule.HOLD_MISMATCH)).isZero();
        assertThat(metrics.mismatches(LectureHpReconciliationRule.SETTLEMENT_MISSING)).isZero();
        assertThat(metrics.lastCompletedEpochSeconds())
                .isEqualTo(finishedAt.atZone(LectureConfig.ZONE).toEpochSecond());
    }

    @Test
    void 완료한_실행이_없으면_기동할_때_지표를_바꾸지_않는다() {
        LocalDateTime finishedAt = LocalDateTime.now(clock).withNano(0).minusHours(5);
        savedRun(LectureHpReconciliationStatus.INCOMPLETE, finishedAt, LectureHpReconciliationRule.HOLD_MISMATCH);
        metrics.updateMismatches(Map.of());
        metrics.updateLastCompleted(finishedAt.minusDays(30));

        restorer.restore();

        assertThat(metrics.mismatches(LectureHpReconciliationRule.HOLD_MISMATCH)).isZero();
        assertThat(metrics.lastCompletedEpochSeconds())
                .isEqualTo(finishedAt.minusDays(30).atZone(LectureConfig.ZONE).toEpochSecond());
    }

    @Test
    void 기동하기_전에_시작해_실행_중으로_남은_실행은_미완료로_끝내고_중단_횟수를_올린다() {
        Long leftOver = support.start(LectureHpReconciliationTrigger.SCHEDULED, null, old);
        double incompleteBefore = metrics.runs(LectureHpReconciliationStatus.INCOMPLETE);
        // 기동한 뒤 시작한 실행은 이 앱이 하고 있는 대사라 건드리지 않는다
        Long startedAfterBoot = transactionTemplate.execute(tx -> runRepository.save(LectureHpReconciliationRun.start(
                LectureHpReconciliationTrigger.SCHEDULED, null, LocalDateTime.now(clock))).getId());

        restorer.restore();

        LectureHpReconciliationRun abandoned = runRepository.findById(leftOver).orElseThrow();
        assertThat(abandoned.getStatus()).isEqualTo(LectureHpReconciliationStatus.INCOMPLETE);
        assertThat(abandoned.getFailureReason()).isEqualTo(LectureHpReconciliationSupport.ABANDONED_REASON);
        assertThat(runRepository.findById(startedAfterBoot).orElseThrow().getStatus())
                .isEqualTo(LectureHpReconciliationStatus.RUNNING);
        assertThat(metrics.runs(LectureHpReconciliationStatus.INCOMPLETE)).isEqualTo(incompleteBefore + 1);
    }

    /** 끝난 실행과 그 실행의 어긋난 건(규칙마다 한 건)을 DB에 바로 남긴다 */
    private void savedRun(LectureHpReconciliationStatus status, LocalDateTime finishedAt,
                          LectureHpReconciliationRule... rules) {
        transactionTemplate.executeWithoutResult(tx -> {
            LectureHpReconciliationRun run = LectureHpReconciliationRun.start(LectureHpReconciliationTrigger.SCHEDULED,
                    null, finishedAt.minusMinutes(1));
            int dead = (int) Arrays.stream(rules)
                    .filter(rule -> rule == LectureHpReconciliationRule.HP_TASK_DEAD).count();
            if (status == LectureHpReconciliationStatus.COMPLETED) {
                run.complete(rules.length, rules.length - dead, dead, finishedAt);
            } else {
                run.incomplete(rules.length, rules.length - dead, dead, "시험용 중단", finishedAt);
            }
            Long runId = runRepository.save(run).getId();
            long orderItemId = 1;
            for (LectureHpReconciliationRule rule : rules) {
                mismatchRepository.save(new LectureHpReconciliationMismatch(runId, orderItemId++, null, rule, null,
                        "시험 강의 상태", "시험 지갑 상태", finishedAt));
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // 강의 데이터: fixture로 상태를 만들고 HP 작업을 changedAt 시각으로 남긴다

    private record Item(Long id, Long orderId) {
    }

    private Item item(OrderItemStatus status, LocalDateTime changedAt) {
        return transactionTemplate.execute(tx -> {
            Lecture lecture = lectureRepository.save(onSaleLecture());
            // 지급하려면 구매 확정 뒤 지급 예정 시각(구매일 + 31일)이 지나야 한다
            boolean payout = status == OrderItemStatus.PAYOUT_PENDING || status == OrderItemStatus.PAID_OUT;
            LocalDateTime purchasedAt = payout ? changedAt.minusDays(40) : changedAt;
            boolean holdStage = status == OrderItemStatus.HOLD_PENDING || status == OrderItemStatus.HOLD_FAILED;
            LectureOrder order = lectureOrderRepository.saveAndFlush(holdStage
                    ? LectureOrderFixture.pending(BUYER_ID, List.of(lecture), purchasedAt)
                    : LectureOrderFixture.order(BUYER_ID, List.of(lecture), purchasedAt));
            Item item = new Item(order.getItems().getFirst().getId(), order.getId());
            LectureHpTask hold = lectureHpTaskRepository.save(task(LectureHpTaskPurpose.ORDER_HOLD, item, null,
                    purchasedAt));
            switch (status) {
                case HOLD_PENDING -> {
                    // 보류 작업이 대기 중이다
                }
                case HOLD_FAILED -> {
                    hold.complete(LectureHpTaskOutcome.REJECTED, changedAt);
                    order.markHoldFailed();
                }
                case PURCHASED -> hold.complete(LectureHpTaskOutcome.SUCCEEDED, changedAt);
                case CONFIRMED -> {
                    hold.complete(LectureHpTaskOutcome.SUCCEEDED, changedAt);
                    order.confirmItem(item.id(), changedAt);
                }
                case CANCEL_PENDING -> {
                    hold.complete(LectureHpTaskOutcome.SUCCEEDED, changedAt);
                    order.requestCancelItem(item.id(), changedAt);
                    lectureHpTaskRepository.save(task(LectureHpTaskPurpose.CANCEL, item, null, changedAt));
                }
                case CANCELED -> {
                    hold.complete(LectureHpTaskOutcome.SUCCEEDED, purchasedAt);
                    LectureOrderFixture.cancel(order, item.id(), changedAt);
                    lectureHpTaskRepository.save(task(LectureHpTaskPurpose.CANCEL, item, null, changedAt))
                            .complete(LectureHpTaskOutcome.SUCCEEDED, changedAt);
                }
                case PAYOUT_PENDING -> {
                    hold.complete(LectureHpTaskOutcome.SUCCEEDED, purchasedAt);
                    order.confirmItem(item.id(), purchasedAt.plusDays(1));
                    order.startPayoutItem(item.id(), changedAt).orElseThrow();
                }
                case PAID_OUT -> {
                    hold.complete(LectureHpTaskOutcome.SUCCEEDED, purchasedAt);
                    order.confirmItem(item.id(), purchasedAt.plusDays(1));
                    LectureOrderFixture.payOut(order, item.id(), changedAt).orElseThrow();
                    lectureHpTaskRepository.save(task(LectureHpTaskPurpose.PAYOUT, item, SELLER_ID, changedAt))
                            .complete(LectureHpTaskOutcome.SUCCEEDED, changedAt);
                }
                default -> throw new IllegalArgumentException("테스트에서 만들지 않는 상태: " + status);
            }
            return item;
        });
    }

    private static LectureHpTask task(LectureHpTaskPurpose purpose, Item item, Long receiverUserId,
                                      LocalDateTime now) {
        return LectureHpTask.create(purpose, "LECTURE-" + purpose + "-" + item.id(), item.orderId(), item.id(),
                BUYER_ID, receiverUserId, PRICE_HP, now);
    }

    // ---------------------------------------------------------------------------------------------
    // 별도 billing 앱에서 실제 HP 기록을 만든다.

    private void seedHold(Long itemId, long amountHp) {
        assertThat(billing.client().hold(new HoldCommand(BUYER_ID, BillingReferenceType.LECTURE_ORDER_ITEM,
                itemId, amountHp))).isInstanceOf(BillingCallResult.Succeeded.class);
        billing.clearCalls();
    }

    private void seedRelease(Long itemId) {
        seedHold(itemId, PRICE_HP);
        assertThat(billing.client().release(ReleaseCommand.full(BUYER_ID,
                BillingReferenceType.LECTURE_ORDER_ITEM, itemId, "PREEXISTING-RETURN-" + itemId)))
                .isInstanceOf(BillingCallResult.Succeeded.class);
        billing.clearCalls();
    }

    private void seedPayout(Long itemId) {
        seedHold(itemId, PRICE_HP);
        assertThat(billing.client().payout(PayoutCommand.full(BUYER_ID, BillingReferenceType.LECTURE_ORDER_ITEM,
                itemId, "PREEXISTING-PAYOUT-" + itemId, SELLER_ID, SettlementSourceType.LECTURE_ORDER_ITEM, itemId)))
                .isInstanceOf(BillingCallResult.Succeeded.class);
        billing.clearCalls();
    }

    private HoldStatePageDto page(Instant after, long afterId, int size) {
        BillingCallResult<HoldStatePageDto> result = billing.client().listHolds(
                BillingReferenceType.LECTURE_ORDER_ITEM, after, afterId, size);
        assertThat(result).isInstanceOf(BillingCallResult.Succeeded.class);
        return ((BillingCallResult.Succeeded<HoldStatePageDto>) result).value();
    }

    private List<HoldQueryCommand> holdQueries() {
        return billing.calls().stream().map(RealBillingTestFixture.Call::command)
                .filter(HoldQueryCommand.class::isInstance).map(HoldQueryCommand.class::cast).toList();
    }

    private List<Long> queriedIds() {
        return holdQueries().stream().flatMap(query -> query.referenceIds().stream()).toList();
    }

    private List<RealBillingTestFixture.HoldListQuery> listQueries() {
        return billing.calls().stream().map(RealBillingTestFixture.Call::command)
                .filter(RealBillingTestFixture.HoldListQuery.class::isInstance)
                .map(RealBillingTestFixture.HoldListQuery.class::cast).toList();
    }
}

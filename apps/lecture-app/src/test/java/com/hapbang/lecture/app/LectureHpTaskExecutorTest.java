package com.hapbang.lecture.app;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.LectureHpAttemptResult;
import com.hapbang.lecture.domain.OrderItemCoupon;
import com.hapbang.lecture.out.OrderItemCouponRepository;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldPromotionDto;
import com.hapbang.shared.billing.dto.PromotionBudgetReserveCommand;
import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.LectureHpTaskAttempt;
import com.hapbang.lecture.domain.LectureHpTaskOutcome;
import com.hapbang.lecture.domain.LectureHpTaskPurpose;
import com.hapbang.lecture.domain.LectureHpTaskStatus;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.LectureOrderStatus;
import com.hapbang.lecture.fake.RecordingCancelResultHandler;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.lecture.out.LectureHpTaskAttemptRepository;
import com.hapbang.lecture.out.LectureHpTaskRepository;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.out.BillingApiClient;
import com.hapbang.shared.billing.out.BillingCallResult;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.billing.RealBillingTestFixture;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

// HP 작업 실행기: 선점 → 트랜잭션 밖 지갑 호출 → 결과 기록, 재시도·DEAD·늦은 결과 (D57, #275)
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class LectureHpTaskExecutorTest {

    private static final String CANCEL_ID = "LECTURE-CANCEL-31";
    private static final long AMOUNT = 10_000L;
    private static final String REGISTERED_LOG = "HP 작업을 등록했다";
    private static final String FINISHED_LOG = "HP 작업을 마쳤다";

    @Autowired
    private LectureHpTaskExecutor executor;

    @Autowired
    private LectureHpTaskSupport lectureHpTaskSupport;

    @Autowired
    private LectureHpTaskRepository lectureHpTaskRepository;

    @Autowired
    private LectureHpTaskAttemptRepository lectureHpTaskAttemptRepository;

    @Autowired
    private RealBillingTestFixture billing;

    @Autowired
    private OrderItemCouponRepository orderItemCouponRepository;

    // 실행기만 확인하도록 운영 CANCEL 처리기 자리에 결과만 남기는 처리기를 넣는다
    @TestBean(methodName = RecordingCancelResultHandler.FACTORY)
    private CancelResultHandler cancelResultHandler;

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    @Qualifier(LectureConfig.CLOCK)
    private Clock clock;

    @BeforeEach
    void setUp() {
        billing.reset();
        billing.wallet(7L, 1_000_000L, 0L);
        seedHold(31L, AMOUNT);
        seedHold(32L, AMOUNT);
        billing.clearCalls();
        cancelHandler().reset();
    }

    @Test
    void 같은_요청_ID에_같은_payload면_기존_작업을_돌려주고_다른_payload면_충돌이다(CapturedOutput output) {
        Long id = register(task(LectureHpTaskPurpose.CANCEL, CANCEL_ID, AMOUNT));

        assertThat(register(task(LectureHpTaskPurpose.CANCEL, CANCEL_ID, AMOUNT))).isEqualTo(id);
        assertThatThrownBy(() -> register(task(LectureHpTaskPurpose.CANCEL, CANCEL_ID, 20_000L)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(lectureHpTaskRepository.count()).isEqualTo(1);
        // 기존 작업을 돌려준 등록은 새 등록이 아니라 INFO를 다시 남기지 않는다
        assertThat(lines(output, REGISTERED_LOG)).hasSize(1);
    }

    @Test
    void HP_작업을_등록하면_커밋_뒤에_INFO_로그를_한_줄_남긴다(CapturedOutput output) {
        Long id = register(task(LectureHpTaskPurpose.CANCEL, CANCEL_ID, AMOUNT));

        assertThat(lines(output, REGISTERED_LOG)).singleElement().satisfies(line -> assertThat(line)
                .contains(" INFO ", "taskId=" + id, "purpose=CANCEL", "requestId=" + CANCEL_ID, "amountHp=" + AMOUNT));
    }

    @Test
    void 등록한_트랜잭션이_롤백되면_등록_로그를_남기지_않는다(CapturedOutput output) {
        transactionTemplate.executeWithoutResult(status -> {
            lectureHpTaskSupport.register(task(LectureHpTaskPurpose.CANCEL, CANCEL_ID, AMOUNT));
            status.setRollbackOnly();
        });

        assertThat(lectureHpTaskRepository.count()).isZero();
        assertThat(lines(output, REGISTERED_LOG)).isEmpty();
    }

    @Test
    void 작업은_업무_트랜잭션_안에서만_등록한다() {
        assertThatThrownBy(() -> lectureHpTaskSupport.register(task(LectureHpTaskPurpose.CANCEL, CANCEL_ID, AMOUNT)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void billing_호출_중에는_강의_트랜잭션이_없고_트랜잭션_안에서_실행하면_실패한다() {
        Long id = register(task(LectureHpTaskPurpose.CANCEL, CANCEL_ID, AMOUNT));

        executor.execute(id);

        assertThat(billing.calls()).singleElement()
                .satisfies(call -> assertThat(call.transactionActive()).isFalse());
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> executor.execute(id)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 성공하면_COMPLETED_SUCCEEDED이고_목적별_처리기를_부른다(CapturedOutput output) {
        Long id = register(task(LectureHpTaskPurpose.CANCEL, CANCEL_ID, AMOUNT));

        executor.execute(id);

        LectureHpTask task = find(id);
        assertThat(task.getStatus()).isEqualTo(LectureHpTaskStatus.COMPLETED);
        assertThat(task.getOutcome()).isEqualTo(LectureHpTaskOutcome.SUCCEEDED);
        assertThat(task.getClaimToken()).isNull();
        assertThat(task.getCompletedAt()).isNotNull();
        assertThat(cancelHandler().events()).containsExactly("succeeded:" + CANCEL_ID);
        assertThat(attempts(id)).singleElement().satisfies(attempt -> {
            assertThat(attempt.getAttemptNo()).isEqualTo(1);
            assertThat(attempt.getResult()).isEqualTo(LectureHpAttemptResult.SUCCEEDED);
            assertThat(attempt.getCompletedAt()).isNotNull();
        });
        assertThat(billing.calls()).singleElement().satisfies(call -> assertThat(call.command())
                .isEqualTo(ReleaseCommand.full(7L, BillingReferenceType.LECTURE_ORDER_ITEM, 31L, CANCEL_ID)));
        assertThat(lines(output, FINISHED_LOG)).singleElement().satisfies(line -> assertThat(line)
                .contains(" INFO ", "taskId=" + id, "taskType=RELEASE", "outcome=SUCCEEDED", "attemptCount=1"));
    }

    @Test
    void HOLD_확정_거절은_COMPLETED_REJECTED다(CapturedOutput output) {
        LectureOrder order = lectureOrderRepository.save(LectureOrderFixture.pending(7L,
                List.of(withId(onSaleLecture(), 10L)), LocalDateTime.now(clock)));
        Long itemId = order.getItems().getFirst().getId();
        Long id = register(LectureHpTask.create(LectureHpTaskPurpose.ORDER_HOLD, "LECTURE-HOLD-" + itemId,
                order.getId(), itemId, 7L, null, PRICE_HP, LocalDateTime.now(clock)));
        billing.reset();
        billing.wallet(7L, 0L, 0L);

        executor.execute(id);

        LectureHpTask task = find(id);
        assertThat(task.getStatus()).isEqualTo(LectureHpTaskStatus.COMPLETED);
        assertThat(task.getOutcome()).isEqualTo(LectureHpTaskOutcome.REJECTED);
        // 운영 처리기가 주문을 결제 실패로 끝낸다(반환할 보류 없음, D58 (3))
        assertThat(lectureOrderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(LectureOrderStatus.HOLD_FAILED);
        assertThat(attempts(id)).singleElement()
                .satisfies(attempt -> assertThat(attempt.getResult()).isEqualTo(LectureHpAttemptResult.TERMINAL_REJECTION));
        assertThat(lines(output, FINISHED_LOG)).singleElement().satisfies(line -> assertThat(line)
                .contains(" INFO ", "taskId=" + id, "taskType=HOLD", "outcome=REJECTED"));
    }

    @Test
    void HOLD가_탈퇴로_닫힌_지갑이면_DEAD가_아니라_COMPLETED_REJECTED다() {
        LectureOrder order = lectureOrderRepository.save(LectureOrderFixture.pending(7L,
                List.of(withId(onSaleLecture(), 10L)), LocalDateTime.now(clock)));
        Long itemId = order.getItems().getFirst().getId();
        Long id = register(LectureHpTask.create(LectureHpTaskPurpose.ORDER_HOLD, "LECTURE-HOLD-" + itemId,
                order.getId(), itemId, 7L, null, PRICE_HP, LocalDateTime.now(clock)));
        billing.reset();
        billing.wallet(7L, 0L, 0L);
        billing.closeWallet(7L);

        executor.execute(id);

        // 잔액 부족과 같은 결제 실패다. 운영 확인 대상(DEAD)으로 남기지 않는다 (D76)
        LectureHpTask task = find(id);
        assertThat(task.getStatus()).isEqualTo(LectureHpTaskStatus.COMPLETED);
        assertThat(task.getOutcome()).isEqualTo(LectureHpTaskOutcome.REJECTED);
        assertThat(lectureOrderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(LectureOrderStatus.HOLD_FAILED);
        assertThat(attempts(id)).singleElement()
                .satisfies(attempt -> assertThat(attempt.getResult()).isEqualTo(LectureHpAttemptResult.TERMINAL_REJECTION));
    }

    @Test
    void 불변식_위반과_결과_금액_불일치는_바로_DEAD다() {
        Long invariant = register(task(LectureHpTaskPurpose.CANCEL, CANCEL_ID, AMOUNT));
        assertThat(billing.client().release(ReleaseCommand.full(7L, BillingReferenceType.LECTURE_ORDER_ITEM,
                31L, "PREEXISTING-RETURN-31"))).isInstanceOf(BillingCallResult.Succeeded.class);
        billing.clearCalls();

        executor.execute(invariant);

        assertThat(find(invariant).getStatus()).isEqualTo(LectureHpTaskStatus.DEAD);
        assertThat(find(invariant).getLastErrorCode()).isEqualTo("HOLD_ALREADY_CLOSED");
        assertThat(attempts(invariant)).singleElement().satisfies(attempt -> {
            assertThat(attempt.getResult()).isEqualTo(LectureHpAttemptResult.INVARIANT_VIOLATION);
            assertThat(attempt.getRawErrorCode()).isEqualTo("HOLD_ALREADY_CLOSED");
        });

        Long mismatch = register(task(LectureHpTaskPurpose.CANCEL, "LECTURE-CANCEL-32", AMOUNT));
        assertThat(billing.client().release(ReleaseCommand.partial(7L, BillingReferenceType.LECTURE_ORDER_ITEM,
                32L, "PREEXISTING-RETURN-32", 1_000L))).isInstanceOf(BillingCallResult.Succeeded.class);
        billing.clearCalls();
        executor.execute(mismatch);

        assertThat(find(mismatch).getStatus()).isEqualTo(LectureHpTaskStatus.DEAD);
        assertThat(find(mismatch).getLastErrorCode()).isEqualTo(LectureHpTaskExecutor.AMOUNT_MISMATCH);
        assertThat(cancelHandler().events()).containsExactly("dead:" + CANCEL_ID, "dead:LECTURE-CANCEL-32");
    }

    @Test
    void 결과_불명이면_다음_시도_시각을_정하고_PENDING으로_돌린다() {
        Long id = register(task(LectureHpTaskPurpose.CANCEL, CANCEL_ID, AMOUNT));
        billing.dropResponses(1);
        LocalDateTime before = LocalDateTime.now(clock);

        executor.execute(id);

        LectureHpTask task = find(id);
        assertThat(task.getStatus()).isEqualTo(LectureHpTaskStatus.PENDING);
        assertThat(task.getAttemptCount()).isEqualTo(1);
        assertThat(task.getLastErrorCode()).isEqualTo("UNKNOWN_RESULT");
        assertThat(task.getNextAttemptAt()).isAfterOrEqualTo(before.plusMinutes(1))
                .isBefore(LocalDateTime.now(clock).plusMinutes(1).plusSeconds(13));
        assertThat(task.getClaimToken()).isNull();
        assertThat(attempts(id)).singleElement().satisfies(attempt -> {
            assertThat(attempt.getResult()).isEqualTo(LectureHpAttemptResult.UNKNOWN_RESULT);
            assertThat(attempt.getRawErrorCode()).isNull();
        });
        assertThat(cancelHandler().events()).isEmpty();
    }

    @Test
    void 선점한_뒤_회로가_막혀_CIRCUIT_OPEN이면_한_번으로_센다() {
        Long id = register(task(LectureHpTaskPurpose.CANCEL, CANCEL_ID, AMOUNT));
        // 선점 전 확인 때는 닫혀 있었지만, 호출할 때는 시험 호출 몫이 없어 Client가 보내지 않았다 (D79 드문 예외)
        billing.duringNextCall(billing::openCircuit);

        executor.execute(id);

        LectureHpTask task = find(id);
        assertThat(task.getStatus()).isEqualTo(LectureHpTaskStatus.PENDING);
        assertThat(task.getAttemptCount()).isEqualTo(1);
        assertThat(attempts(id)).singleElement().satisfies(attempt -> {
            assertThat(attempt.getResult()).isEqualTo(LectureHpAttemptResult.RETRYABLE);
            assertThat(attempt.getRawErrorCode()).isEqualTo(BillingApiClient.CIRCUIT_OPEN);
        });
    }

    @Test
    void 회로가_열려_있으면_선점하지_않아_시도_횟수와_시도_행이_그대로다() {
        Long id = register(task(LectureHpTaskPurpose.CANCEL, CANCEL_ID, AMOUNT));
        billing.openCircuit();

        for (int i = 0; i < 20; i++) {
            executor.execute(id);
        }

        LectureHpTask task = find(id);
        assertThat(task.getStatus()).isEqualTo(LectureHpTaskStatus.PENDING);
        assertThat(task.getAttemptCount()).isZero();
        assertThat(task.getLastFailedAt()).isNull();
        assertThat(attempts(id)).isEmpty();
        assertThat(billing.calls()).isEmpty();

        billing.closeCircuit();
        executor.execute(id);

        assertThat(find(id).getStatus()).isEqualTo(LectureHpTaskStatus.COMPLETED);
    }

    @Test
    void 열_번째_시도도_실패하면_DEAD이고_오류_로그와_지표를_남긴다(CapturedOutput output) {
        Long id = register(task(LectureHpTaskPurpose.CANCEL, CANCEL_ID, AMOUNT));
        double deadBefore = deadCount();

        billing.disconnect();
        try {
            for (int i = 0; i < 10; i++) {
                // 실제 통신 장애를 열 번 보내되 각 작업 시도 전 실제 회로를 닫는다.
                billing.closeCircuit();
                executor.execute(id);
                makeDue(id);
            }
        } finally {
            billing.reconnect();
        }

        LectureHpTask task = find(id);
        assertThat(task.getStatus()).isEqualTo(LectureHpTaskStatus.DEAD);
        assertThat(task.getAttemptCount()).isEqualTo(10);
        assertThat(attempts(id)).hasSize(10)
                .allSatisfy(attempt -> assertThat(attempt.getResult()).isEqualTo(LectureHpAttemptResult.RETRYABLE));
        assertThat(output).contains(LectureHpTaskExecutor.DEAD_LOG);
        assertThat(deadCount()).isEqualTo(deadBefore + 1);
        assertThat(cancelHandler().events()).containsExactly("dead:" + CANCEL_ID);

        executor.execute(id);
        assertThat(billing.calls()).hasSize(10);
    }

    @Test
    void lease가_지난_작업을_다시_선점하면_앞_시도를_LEASE_EXPIRED로_닫는다() {
        Long id = register(task(LectureHpTaskPurpose.CANCEL, CANCEL_ID, AMOUNT));
        claimAndAbandon(id);
        makeDue(id);

        executor.execute(id);

        assertThat(find(id).getStatus()).isEqualTo(LectureHpTaskStatus.COMPLETED);
        assertThat(find(id).getTotalAttemptCount()).isEqualTo(2);
        assertThat(attempts(id)).extracting(LectureHpTaskAttempt::getResult)
                .containsExactly(LectureHpAttemptResult.LEASE_EXPIRED, LectureHpAttemptResult.SUCCEEDED);
    }

    @Test
    void 한도에_닿은_채_lease가_지나면_새로_선점하지_않고_DEAD다() {
        Long id = register(task(LectureHpTaskPurpose.CANCEL, CANCEL_ID, AMOUNT));
        claimAndAbandon(id);
        jdbcTemplate.update("update lectures.lecture_hp_task set attempt_count = 10 where id = ?", id);
        makeDue(id);

        executor.execute(id);

        LectureHpTask task = find(id);
        assertThat(task.getStatus()).isEqualTo(LectureHpTaskStatus.DEAD);
        assertThat(task.getLastErrorCode()).isEqualTo(LectureHpTaskExecutor.LEASE_EXPIRED);
        assertThat(attempts(id)).singleElement()
                .satisfies(attempt -> assertThat(attempt.getResult()).isEqualTo(LectureHpAttemptResult.LEASE_EXPIRED));
        assertThat(billing.calls()).isEmpty();
        assertThat(cancelHandler().events()).containsExactly("dead:" + CANCEL_ID);
    }

    @Test
    void 늦게_온_결과는_claim_token이_달라_무시한다(CapturedOutput output) {
        Long id = register(task(LectureHpTaskPurpose.CANCEL, CANCEL_ID, AMOUNT));
        AtomicBoolean first = new AtomicBoolean(true);
        // 첫 호출이 응답하기 전에 선점이 만료되고, 다른 실행이 다시 선점해 끝낸다
        billing.duringNextCall(() -> {
            if (first.getAndSet(false)) {
                makeDue(id);
                executor.execute(id);
            }
        });

        executor.execute(id);

        assertThat(billing.calls()).hasSize(2).extracting(call -> ((ReleaseCommand) call.command()).releaseRequestId())
                .containsOnly(CANCEL_ID);
        assertThat(cancelHandler().events()).containsExactly("succeeded:" + CANCEL_ID);
        assertThat(attempts(id)).extracting(LectureHpTaskAttempt::getResult)
                .containsExactly(LectureHpAttemptResult.LEASE_EXPIRED, LectureHpAttemptResult.SUCCEEDED);
        assertThat(output).contains("HP 작업 결과를 버린다");
    }

    @Test
    void 결과_기록이_실패하면_lease_뒤에_같은_요청_ID로_다시_실행한다() {
        Long id = register(task(LectureHpTaskPurpose.CANCEL, CANCEL_ID, AMOUNT));
        cancelHandler().failNext(new IllegalStateException("결과 기록 실패"));

        executor.execute(id);

        assertThat(find(id).getStatus()).isEqualTo(LectureHpTaskStatus.PROCESSING);
        makeDue(id);
        executor.execute(id);

        assertThat(find(id).getStatus()).isEqualTo(LectureHpTaskStatus.COMPLETED);
        assertThat(billing.calls()).hasSize(2).extracting(call -> ((ReleaseCommand) call.command()).releaseRequestId())
                .containsOnly(CANCEL_ID);
        assertThat(cancelHandler().events()).containsExactly("succeeded:" + CANCEL_ID);
    }

    @Test
    void 두_실행기가_동시에_실행해도_지갑은_한_번만_부른다() throws Exception {
        Long id = register(task(LectureHpTaskPurpose.CANCEL, CANCEL_ID, AMOUNT));
        CountDownLatch calling = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        billing.duringNextCall(() -> {
            calling.countDown();
            await(release);
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstRun = pool.submit(() -> executor.execute(id));
            assertThat(calling.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> secondRun = pool.submit(() -> executor.execute(id));
            secondRun.get(5, TimeUnit.SECONDS);
            release.countDown();
            firstRun.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(billing.calls()).hasSize(1);
        assertThat(find(id).getStatus()).isEqualTo(LectureHpTaskStatus.COMPLETED);
        assertThat(attempts(id)).hasSize(1);
    }

    @Test
    void 구매자_0원도_플랫폼_보류를_보내고_쿠폰_예약_세대를_대조한다() {
        LectureOrder order = platformOrder(PRICE_HP);
        Long itemId = order.getItems().getFirst().getId();
        Long id = register(LectureHpTask.create(LectureHpTaskPurpose.ORDER_HOLD, "anchor-hold-" + itemId,
                order.getId(), itemId, 7L, null, 0L, LocalDateTime.now(clock)));

        executor.execute(id);

        assertThat(find(id).getStatus()).isEqualTo(LectureHpTaskStatus.COMPLETED);
        assertThat(billing.calls()).singleElement().satisfies(call -> {
            assertThat(call.transactionActive()).isFalse();
            assertThat((HoldCommand) call.command()).extracting(HoldCommand::amountHp).isEqualTo(0L);
            assertThat(((HoldCommand) call.command()).promotions())
                    .containsExactly(new HoldPromotionDto(42, 901, 3, PRICE_HP));
        });
    }

    @Test
    void 두_재원이_모두_0인_작업은_지갑을_부르지_않고_불변식_오류로_멈춘다() {
        Long id = register(task(LectureHpTaskPurpose.CANCEL, "zero", 0));
        executor.execute(id);
        assertThat(billing.calls()).isEmpty();
        assertThat(find(id).getStatus()).isEqualTo(LectureHpTaskStatus.DEAD);
        assertThat(find(id).getLastErrorCode()).isEqualTo("PROMOTION_PAYLOAD_INVALID");
    }

    @Test
    void 반환_총액이_맞아도_플랫폼_복구의_사용_세대가_다르면_완료하지_않는다() {
        LectureOrder order = platformOrder(1_000);
        Long itemId = order.getItems().getFirst().getId();
        Long id = register(LectureHpTask.create(LectureHpTaskPurpose.CANCEL, "platform-cancel-" + itemId,
                order.getId(), itemId, 7L, null, PRICE_HP - 1_000, LocalDateTime.now(clock)));
        // billing에만 이전 예약 세대가 보류되어 실제 반환도 그 세대로 돌아온다.
        assertThat(billing.client().hold(new HoldCommand(7L, BillingReferenceType.LECTURE_ORDER_ITEM, itemId,
                PRICE_HP - 1_000L, List.of(new HoldPromotionDto(42L, 901L, 2L, 1_000L)))))
                .isInstanceOf(BillingCallResult.Succeeded.class);
        billing.clearCalls();

        executor.execute(id);

        assertThat(find(id).getStatus()).isEqualTo(LectureHpTaskStatus.DEAD);
        assertThat(find(id).getLastErrorCode()).isEqualTo(LectureHpTaskExecutor.AMOUNT_MISMATCH);
    }

    private LectureOrder platformOrder(long platformHp) {
        billing.promotionWallet(PRICE_HP);
        assertThat(billing.client().reserveBudget(new PromotionBudgetReserveCommand("RESERVE-42", 42L, 1, PRICE_HP)))
                .isInstanceOf(BillingCallResult.Succeeded.class);
        billing.clearCalls();
        LectureOrder order = LectureOrderFixture.pending(7L, List.of(withId(onSaleLecture(), 10L)),
                LocalDateTime.now(clock));
        order.applyItemDiscount(10L, platformHp, platformHp, 0L);
        LectureOrder saved = lectureOrderRepository.save(order);
        OrderItemCoupon coupon = OrderItemCoupon.create(saved.getItems().getFirst().getId(), 901L, 42L, "PLATFORM",
                0, PRICE_HP, platformHp, platformHp, 0, 2, 1);
        coupon.recordReserved(3L);
        orderItemCouponRepository.save(coupon);
        return saved;
    }

    private void seedHold(long itemId, long amountHp) {
        assertThat(billing.client().hold(new HoldCommand(7L, BillingReferenceType.LECTURE_ORDER_ITEM, itemId,
                amountHp))).isInstanceOf(BillingCallResult.Succeeded.class);
    }

    private LectureHpTask task(LectureHpTaskPurpose purpose, String requestId, long amount) {
        return LectureHpTask.create(purpose, requestId, 3L, requestId.endsWith("-32") ? 32L : 31L, 7L, null,
                amount, LocalDateTime.now(clock));
    }

    private static List<String> lines(CapturedOutput output, String text) {
        return output.getAll().lines().filter(line -> line.contains(text)).toList();
    }

    private Long register(LectureHpTask task) {
        return transactionTemplate.execute(status -> lectureHpTaskSupport.register(task).getId());
    }

    private LectureHpTask find(Long id) {
        return lectureHpTaskRepository.findById(id).orElseThrow();
    }

    private List<LectureHpTaskAttempt> attempts(Long id) {
        return lectureHpTaskAttemptRepository.findByTaskIdOrderByAttemptNo(id);
    }

    /** 실행기가 선점한 뒤 결과를 남기지 못하고 끝난 상황(프로세스 종료)을 만든다 */
    private void claimAndAbandon(Long id) {
        LocalDateTime now = LocalDateTime.now(clock);
        UUID token = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status -> {
            assertThat(lectureHpTaskRepository.claim(id, token, now, now.plusMinutes(5), 10)).isEqualTo(1);
            lectureHpTaskAttemptRepository.save(LectureHpTaskAttempt.start(id, 1, token, now));
        });
    }

    /** 다음 시도 시각과 선점 만료 시각을 지난 시각으로 당긴다 */
    private void makeDue(Long id) {
        LocalDateTime past = LocalDateTime.now(clock).minusMinutes(10);
        jdbcTemplate.update("""
                update lectures.lecture_hp_task
                set next_attempt_at = ?, lease_until = case when lease_until is null then null else ? end
                where id = ?""", past, past, id);
    }

    private double deadCount() {
        Counter counter = meterRegistry.find("lecture.hp_task.dead").counter();
        return counter == null ? 0 : counter.count();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private RecordingCancelResultHandler cancelHandler() {
        return (RecordingCancelResultHandler) cancelResultHandler;
    }
}

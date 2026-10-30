package com.hapbang.lecture.app;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.ObjectionStatus;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.domain.OrderItemStatus;
import com.hapbang.lecture.fake.FakeBillingApiClient;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.lecture.out.LectureHpTaskRepository;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.lecture.exception.LectureObjectionRefusedException;
import com.hapbang.shared.lecture.exception.LectureObjectionRefusedException.Reason;
import com.hapbang.shared.lecture.port.LectureObjectionPort;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 이의 제기(#156)와 지급 배치. 실제 이의 제기 포트로 접수·판단한 항목이 지급 대상에서 빠지거나 다시 들어오는지 확인한다.
 * <p>
 * 접수와 지급 배치의 경쟁 (설계 6.2 "지급 배치와 경쟁", 10장). 둘 다 항목 행을 바꾸므로 {@code order_item.version}
 * 낙관적 락으로 직렬화된다. 접수가 먼저면 지급 대상에서 빠지고, 지급이 먼저면 접수가 {@code ALREADY_PAID_OUT}으로 거부된다.
 * <p>
 * 접수는 지급 예정 시각 전에만, 지급은 그 뒤에만 되므로 실제 경쟁은 접수가 기한 직전에 시작해 배치가 읽은 뒤 커밋되는 경우다.
 * 겹치는 경우는 지급 예정 시각이 지난 항목에 기한 직전 시각으로 접수 도메인 메서드를 불러 재현한다.
 */
@IntegrationTest
class OrderPayoutObjectionTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final Long INQUIRY_ID = 500L;
    private static final String REASON = "강의 소개와 영상 내용이 다릅니다.";

    @Autowired
    private OrderPayoutUseCase orderPayoutUseCase;

    @Autowired
    private LectureObjectionPort lectureObjectionPort;

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private FakeBillingApiClient billing;

    @Autowired
    private LectureHpTaskBatchUseCase lectureHpTaskBatchUseCase;

    @Autowired
    private LectureHpTaskRepository lectureHpTaskRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 지급 배치가 항목을 읽은 직후에 멈추게 해 그 사이에 접수를 커밋한다
    @MockitoSpyBean
    private LectureOrderSupport lectureOrderSupport;

    @Autowired
    @Qualifier(LectureConfig.CLOCK)
    private Clock clock;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @BeforeEach
    void setUp() {
        billing.reset();
        billing.setSettledHp(PRICE_HP);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void 기각된_항목은_지급_시각이_지나면_지급하고_인정된_항목은_지급하지_않는다() {
        Long rejected = itemId(saveConfirmed(now().minusDays(20)));
        Long accepted = itemId(saveConfirmed(now().minusDays(20)));
        lectureObjectionPort.fileObjection(rejected, BUYER_ID, INQUIRY_ID, REASON);
        lectureObjectionPort.fileObjection(accepted, BUYER_ID, INQUIRY_ID + 1, REASON);
        lectureObjectionPort.resolveObjection(rejected, INQUIRY_ID, false, ADMIN_ID);
        lectureObjectionPort.resolveObjection(accepted, INQUIRY_ID + 1, true, ADMIN_ID);
        // 판단 뒤 지급 예정 시각이 지났다
        jdbcTemplate.update("update lectures.order_item set payout_scheduled_at = ? where id in (?, ?)",
                now().minusDays(1), rejected, accepted);

        orderPayoutUseCase.payOutDueItems();
        lectureHpTaskBatchUseCase.runDue();

        assertThat(payouts()).extracting(PayoutCommand::referenceId).containsExactly(rejected);
        assertThat(item(rejected)).extracting(OrderItem::getStatus, OrderItem::getObjectionStatus)
                .containsExactly(OrderItemStatus.PAID_OUT, ObjectionStatus.REJECTED);
        assertThat(item(accepted)).extracting(OrderItem::getStatus, OrderItem::getObjectionStatus)
                .containsExactly(OrderItemStatus.RETURNED, ObjectionStatus.ACCEPTED);
    }

    @Test
    void 접수가_먼저_커밋되면_지급_시각이_지나도_지급하지_않는다() {
        Long itemId = itemId(saveConfirmed(now().minusDays(20)));
        lectureObjectionPort.fileObjection(itemId, BUYER_ID, INQUIRY_ID, REASON);
        // 접수 뒤 지급 예정 시각이 지났다
        jdbcTemplate.update("update lectures.order_item set payout_scheduled_at = ? where id = ?",
                now().minusDays(1), itemId);

        orderPayoutUseCase.payOutDueItems();

        assertThat(lectureHpTaskRepository.count()).isZero();
        assertThat(item(itemId)).extracting(OrderItem::getStatus, OrderItem::getObjectionStatus)
                .containsExactly(OrderItemStatus.CONFIRMED, ObjectionStatus.FILED);
    }

    @Test
    void 철회한_항목은_지급_예정_시각이_지났으면_다음_지급_배치에서_지급한다() {
        Long itemId = itemId(saveConfirmed(now().minusDays(20)));
        lectureObjectionPort.fileObjection(itemId, BUYER_ID, INQUIRY_ID, REASON);
        // 접수 중에 지급 예정 시각이 지나 지급 배치가 건너뛴다
        jdbcTemplate.update("update lectures.order_item set payout_scheduled_at = ? where id = ?",
                now().minusDays(1), itemId);
        orderPayoutUseCase.payOutDueItems();
        assertThat(lectureHpTaskRepository.count()).isZero();

        lectureObjectionPort.withdrawObjection(itemId, BUYER_ID, INQUIRY_ID);
        orderPayoutUseCase.payOutDueItems();
        lectureHpTaskBatchUseCase.runDue();

        assertThat(payouts()).extracting(PayoutCommand::referenceId).containsExactly(itemId);
        assertThat(item(itemId)).extracting(OrderItem::getStatus, OrderItem::getObjectionStatus)
                .containsExactly(OrderItemStatus.PAID_OUT, ObjectionStatus.NONE);
    }

    @Test
    void 지급을_시작하면_접수는_PERIOD_EXPIRED이고_지급이_끝나면_ALREADY_PAID_OUT이다() {
        Long itemId = itemId(saveConfirmed(now().minusDays(32)));
        orderPayoutUseCase.payOutDueItems();

        // 지급 처리 중은 지급 예정 시각이 지난 뒤라 기한이 지났다. 지급 작업이 멈춰도 기한은 다시 열리지 않는다 (D59 (6))
        assertRefused(() -> lectureObjectionPort.fileObjection(itemId, BUYER_ID, INQUIRY_ID, REASON),
                Reason.PERIOD_EXPIRED);
        lectureHpTaskBatchUseCase.runDue();
        assertRefused(() -> lectureObjectionPort.fileObjection(itemId, BUYER_ID, INQUIRY_ID, REASON),
                Reason.ALREADY_PAID_OUT);
        assertThat(item(itemId)).extracting(OrderItem::getStatus, OrderItem::getObjectionStatus)
                .containsExactly(OrderItemStatus.PAID_OUT, ObjectionStatus.NONE);
    }

    @Test
    void 지급_배치가_읽은_뒤_접수가_커밋되면_낙관적_락_충돌로_지급하지_않는다() throws Exception {
        Long itemId = itemId(saveConfirmed(now().minusDays(32)));
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch filed = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object order = invocation.callRealMethod();
            loaded.countDown();
            assertThat(filed.await(10, TimeUnit.SECONDS)).isTrue();
            return order;
        }).when(lectureOrderSupport).findOrderByItemId(eq(itemId));

        Future<?> batch = executor.submit(() -> orderPayoutUseCase.payOutDueItems());
        assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
        fileObjectionBeforeDeadline(itemId);
        filed.countDown();
        batch.get(10, TimeUnit.SECONDS);

        // 배치의 항목 변경은 version이 달라 flush에서 실패하고, PAYOUT 작업 등록까지 함께 롤백된다
        assertThat(lectureHpTaskRepository.count()).isZero();
        assertThat(item(itemId)).extracting(OrderItem::getStatus, OrderItem::getObjectionStatus)
                .containsExactly(OrderItemStatus.CONFIRMED, ObjectionStatus.FILED);
    }

    @Test
    void 접수가_읽은_뒤_지급_시작이_커밋되면_접수가_낙관적_락_충돌로_실패하고_다시_접수하면_PERIOD_EXPIRED다() throws Exception {
        Long itemId = itemId(saveConfirmed(now().minusDays(32)));
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch paid = new CountDownLatch(1);

        Future<?> filing = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            LectureOrder order = lectureOrderRepository.findByItemId(itemId).orElseThrow();
            // 항목은 지연 로딩이므로 지급이 커밋되기 전에 읽어 둔다
            LocalDateTime deadline = deadlineOf(order, itemId);
            loaded.countDown();
            await(paid);
            order.fileObjection(itemId, BUYER_ID, INQUIRY_ID, REASON, deadline.minusSeconds(1));
            lectureOrderRepository.flush();
        }));
        assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
        orderPayoutUseCase.payOutDueItems();
        paid.countDown();

        assertThatThrownBy(() -> filing.get(10, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertRefused(() -> lectureObjectionPort.fileObjection(itemId, BUYER_ID, INQUIRY_ID, REASON),
                Reason.PERIOD_EXPIRED);
        lectureHpTaskBatchUseCase.runDue();
        assertThat(payouts()).extracting(PayoutCommand::referenceId).containsExactly(itemId);
        assertThat(item(itemId)).extracting(OrderItem::getStatus, OrderItem::getObjectionStatus)
                .containsExactly(OrderItemStatus.PAID_OUT, ObjectionStatus.NONE);
    }

    /** 지급 예정 시각 직전에 시작한 접수가 지금 커밋된다 (접수 도메인 규칙을 그대로 거친다) */
    private void fileObjectionBeforeDeadline(Long itemId) {
        transactionTemplate.executeWithoutResult(status -> {
            LectureOrder order = lectureOrderRepository.findByItemId(itemId).orElseThrow();
            order.fileObjection(itemId, BUYER_ID, INQUIRY_ID, REASON, deadlineOf(order, itemId).minusSeconds(1));
        });
    }

    private static LocalDateTime deadlineOf(LectureOrder order, Long itemId) {
        return order.getItems().stream().filter(item -> item.getId().equals(itemId)).findFirst().orElseThrow()
                .getPayoutScheduledAt();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void assertRefused(Runnable action, Reason reason) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(LectureObjectionRefusedException.class,
                        exception -> assertThat(exception.getReason()).isEqualTo(reason));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private List<PayoutCommand> payouts() {
        return billing.calls().stream()
                .map(FakeBillingApiClient.Call::command)
                .filter(PayoutCommand.class::isInstance)
                .map(PayoutCommand.class::cast)
                .toList();
    }

    /** {@code purchasedAt}에 사서 바로 구매 확정한 주문 */
    private LectureOrder saveConfirmed(LocalDateTime purchasedAt) {
        Lecture lecture = lectureRepository.save(onSaleLecture());
        LectureOrder order = lectureOrderRepository.save(LectureOrderFixture.order(BUYER_ID, List.of(lecture),
                purchasedAt));
        Long itemId = itemId(order);
        transactionTemplate.executeWithoutResult(status ->
                lectureOrderRepository.findById(order.getId()).orElseThrow().confirmItem(itemId, purchasedAt));
        return order;
    }

    private static Long itemId(LectureOrder order) {
        return order.getItems().getFirst().getId();
    }

    private OrderItem item(Long itemId) {
        return transactionTemplate.execute(status -> {
            LectureOrder order = lectureOrderRepository.findByItemId(itemId).orElseThrow();
            return order.getItems().stream().filter(item -> item.getId().equals(itemId)).findFirst().orElseThrow();
        });
    }
}

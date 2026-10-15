package com.hapbang.lecture.app;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.ObjectionStatus;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.domain.OrderItemStatus;
import com.hapbang.lecture.fake.FakeBillingClient;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.lecture.out.billing.BillingCallException;
import com.hapbang.lecture.out.billing.BillingCallFailureKind;
import com.hapbang.shared.billing.domain.BillingFailureCode;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 지급 배치의 처리 메서드를 직접 호출해 DB와 billing 호출까지 확인한다(배치 빈은 모듈 테스트에서 꺼져 있다).
 * 실제 강의 Clock을 쓰므로 지급 시각이 지난 주문은 32일 전 구매로 만든다. 기한 경계는 {@code LectureOrderTest}에서 확인한다.
 * Fixture 주문에는 보류가 없으므로 {@link FakeBillingClient#assumeHeld}로 항목 가격만큼 보류가 있다고 정한다.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class OrderPayoutUseCaseTest {

    private static final Long BUYER_ID = OTHER_USER_ID;

    @Autowired
    private OrderPayoutUseCase orderPayoutUseCase;

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private FakeBillingClient fakeBillingClient;

    @Autowired
    private LecturePayoutAdminFacade lecturePayoutAdminFacade;

    // 한 항목의 처리 실패를 만들기 위해 항목별 주문 조회에서 예외를 던진다
    @MockitoSpyBean
    private LectureOrderSupport lectureOrderSupport;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    @Qualifier(LectureConfig.CLOCK)
    private Clock clock;

    @BeforeEach
    void setUp() {
        fakeBillingClient.reset();
    }

    @Test
    void 지급_시각이_지난_구매_확정_항목만_판매자에게_지급한다() {
        LocalDateTime overdue = now().minusDays(32);
        LectureOrder due = saveConfirmed(overdue);
        LectureOrder notDue = saveConfirmed(now().minusDays(20));
        LectureOrder purchased = saveHeld(overdue);
        LocalDateTime before = now();

        orderPayoutUseCase.payOutDueItems();

        Long dueItemId = itemId(due);
        assertThat(fakeBillingClient.payouts()).containsExactly(PayoutCommand.full(BUYER_ID,
                BillingReferenceType.LECTURE_ORDER_ITEM, dueItemId, "LECTURE-PAYOUT-" + dueItemId, SELLER_ID,
                SettlementSourceType.LECTURE_ORDER_ITEM, dueItemId));
        assertThat(items(due)).singleElement().satisfies(item -> {
            assertThat(item.getStatus()).isEqualTo(OrderItemStatus.PAID_OUT);
            assertThat(item.getPaidOutAt()).isAfterOrEqualTo(before);
        });
        assertThat(items(notDue)).extracting(OrderItem::getStatus).containsExactly(OrderItemStatus.CONFIRMED);
        assertThat(items(purchased)).extracting(OrderItem::getStatus).containsExactly(OrderItemStatus.PURCHASED);
    }

    @Test
    void 이의_제기_접수_중인_항목은_지급하지_않는다() {
        LectureOrder filed = saveConfirmed(now().minusDays(32));
        // 이의 제기 접수(#156)가 아직 없어 상태만 넣는다
        inTransaction(filed, order -> ReflectionTestUtils.setField(order.getItems().getFirst(), "objectionStatus",
                ObjectionStatus.FILED));

        orderPayoutUseCase.payOutDueItems();

        assertThat(fakeBillingClient.payouts()).isEmpty();
        assertThat(items(filed)).extracting(OrderItem::getStatus).containsExactly(OrderItemStatus.CONFIRMED);
    }

    @Test
    void billing_지급이_실패하면_항목은_구매_확정으로_남는다() {
        LectureOrder failing = saveConfirmed(now().minusDays(32));
        fakeBillingClient.failPayoutFor(itemId(failing));

        orderPayoutUseCase.payOutDueItems();

        assertThat(fakeBillingClient.payouts()).isEmpty();
        assertThat(items(failing)).extracting(OrderItem::getStatus, OrderItem::getPaidOutAt)
                .containsExactly(tuple(OrderItemStatus.CONFIRMED, null));
    }

    @Test
    void 지급액이_가격과_다르면_항목_상태를_롤백하고_격리한다() {
        LectureOrder order = saveConfirmed(now().minusDays(32));
        fakeBillingClient.overridePaidHp(PRICE_HP - 1);

        orderPayoutUseCase.payOutDueItems();

        assertThat(fakeBillingClient.payouts()).isEmpty();
        assertThat(items(order)).singleElement().satisfies(item -> {
            assertThat(item.getStatus()).isEqualTo(OrderItemStatus.CONFIRMED);
            assertThat(item.getLastPayoutFailureCode()).isEqualTo("PAYOUT_AMOUNT_MISMATCH");
            assertThat(item.isPayoutQuarantined()).isTrue();
        });
    }

    @Test
    void 영구_오류는_첫_실패에_격리되고_다음_실행에서_대상에서_빠진다() {
        LectureOrder order = saveConfirmed(now().minusDays(32));
        fakeBillingClient.failPayoutFor(itemId(order), BillingFailureCode.PAYOUT_ALREADY_SETTLED, "이미 지급했습니다.");
        LocalDateTime before = now();

        orderPayoutUseCase.payOutDueItems();
        orderPayoutUseCase.payOutDueItems();

        // 두 번째 실행은 billing을 부르지 않아 횟수가 늘지 않는다. 지급 완료로 보지 않으므로 CONFIRMED로 남는다
        assertThat(fakeBillingClient.payouts()).isEmpty();
        assertThat(items(order)).singleElement().satisfies(item -> {
            assertThat(item.getStatus()).isEqualTo(OrderItemStatus.CONFIRMED);
            assertThat(item.getPayoutFailureCount()).isEqualTo(1);
            assertThat(item.getLastPayoutFailureCode()).isEqualTo("PAYOUT_ALREADY_SETTLED");
            assertThat(item.getLastPayoutFailedAt()).isAfterOrEqualTo(before);
            assertThat(item.getPayoutQuarantinedAt()).isEqualTo(item.getLastPayoutFailedAt());
        });
    }

    @Test
    void 일시_오류는_실패_횟수가_늘고_다음_실행에서_다시_시도된다() {
        LectureOrder order = saveConfirmed(now().minusDays(32));
        fakeBillingClient.failPayoutFor(itemId(order));

        orderPayoutUseCase.payOutDueItems();
        orderPayoutUseCase.payOutDueItems();

        assertThat(items(order)).singleElement().satisfies(item -> {
            assertThat(item.getStatus()).isEqualTo(OrderItemStatus.CONFIRMED);
            assertThat(item.getPayoutFailureCount()).isEqualTo(2);
            assertThat(item.getLastPayoutFailureCode()).isEqualTo("WALLET_NOT_FOUND");
            assertThat(item.isPayoutQuarantined()).isFalse();
        });
    }

    @Test
    void billing_결과를_받지_못한_지급_실패는_원인_예외_이름으로_일시_실패를_기록한다() {
        LectureOrder order = saveConfirmed(now().minusDays(32));
        fakeBillingClient.throwOnPayout(itemId(order), new BillingCallException(BillingCallFailureKind.RETRYABLE,
                new CannotAcquireLockException("deadlock detected")));

        orderPayoutUseCase.payOutDueItems();

        assertThat(items(order)).singleElement().satisfies(item -> {
            assertThat(item.getStatus()).isEqualTo(OrderItemStatus.CONFIRMED);
            assertThat(item.getPayoutFailureCount()).isEqualTo(1);
            assertThat(item.getLastPayoutFailureCode()).isEqualTo("CannotAcquireLockException");
            assertThat(item.isPayoutQuarantined()).isFalse();
        });
    }

    @Test
    void 일시_오류는_세_번째_실패부터_ERROR로_남는다(CapturedOutput output) {
        LectureOrder order = saveConfirmed(now().minusDays(32));
        fakeBillingClient.failPayoutFor(itemId(order));
        String repeated = "지급 실패가 반복된다, 다음 실행에서 다시 처리한다 orderItemId=" + itemId(order) + ",";

        orderPayoutUseCase.payOutDueItems();
        orderPayoutUseCase.payOutDueItems();
        assertThat(output.getAll()).doesNotContain(repeated);

        orderPayoutUseCase.payOutDueItems();
        assertThat(output.getAll())
                .containsPattern("ERROR .*" + Pattern.quote(repeated + " code=WALLET_NOT_FOUND, failures=3"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 격리를_해제하면_다음_실행에서_지급되고_실패_기록은_남는다() {
        LectureOrder order = saveConfirmed(now().minusDays(32));
        fakeBillingClient.failPayoutFor(itemId(order), BillingFailureCode.HOLD_ALREADY_CLOSED, "남은 보류 HP가 없습니다.");
        orderPayoutUseCase.payOutDueItems();
        fakeBillingClient.failPayoutFor(null);

        lecturePayoutAdminFacade.releasePayoutQuarantine(ADMIN_ID, itemId(order));
        orderPayoutUseCase.payOutDueItems();

        assertThat(fakeBillingClient.payouts()).extracting(PayoutCommand::referenceId).containsExactly(itemId(order));
        assertThat(items(order)).singleElement().satisfies(item -> {
            assertThat(item.getStatus()).isEqualTo(OrderItemStatus.PAID_OUT);
            assertThat(item.getPayoutFailureCount()).isEqualTo(1);
            assertThat(item.getLastPayoutFailureCode()).isEqualTo("HOLD_ALREADY_CLOSED");
            assertThat(item.isPayoutQuarantined()).isFalse();
        });
    }

    @Test
    void 한_항목_처리가_실패해도_다음_항목을_처리한다() {
        LectureOrder failing = saveConfirmed(now().minusDays(33));
        LectureOrder next = saveConfirmed(now().minusDays(32));
        doThrow(new IllegalStateException("처리 실패")).when(lectureOrderSupport)
                .findOrderByItemId(eq(itemId(failing)));

        assertThatCode(() -> orderPayoutUseCase.payOutDueItems()).doesNotThrowAnyException();

        assertThat(items(failing)).extracting(OrderItem::getStatus).containsExactly(OrderItemStatus.CONFIRMED);
        assertThat(items(next)).extracting(OrderItem::getStatus).containsExactly(OrderItemStatus.PAID_OUT);
    }

    @Test
    void 실패한_항목은_다음_실행에서_지급된다() {
        LectureOrder failing = saveConfirmed(now().minusDays(32));
        fakeBillingClient.failPayoutFor(itemId(failing));
        orderPayoutUseCase.payOutDueItems();
        fakeBillingClient.failPayoutFor(null);

        orderPayoutUseCase.payOutDueItems();

        // 지급에 성공해도 실패 기록은 이력으로 남는다 (D54)
        assertThat(items(failing)).extracting(OrderItem::getStatus, OrderItem::getPayoutFailureCount)
                .containsExactly(tuple(OrderItemStatus.PAID_OUT, 1));
        assertThat(fakeBillingClient.payouts()).extracting(PayoutCommand::referenceId).containsExactly(itemId(failing));
    }

    @Test
    void 조회_실패도_다음_실행에서_다시_지급한다() {
        LectureOrder failing = saveConfirmed(now().minusDays(32));
        doThrow(new IllegalStateException("처리 실패")).when(lectureOrderSupport)
                .findOrderByItemId(eq(itemId(failing)));
        orderPayoutUseCase.payOutDueItems();
        // 실패 기록은 지급 조회와 다른 경로로 읽어, 조회가 실패한 항목에도 남는다
        assertThat(items(failing)).extracting(OrderItem::getPayoutFailureCount, OrderItem::getLastPayoutFailureCode)
                .containsExactly(tuple(1, "IllegalStateException"));
        reset(lectureOrderSupport);

        orderPayoutUseCase.payOutDueItems();

        assertThat(items(failing)).extracting(OrderItem::getStatus).containsExactly(OrderItemStatus.PAID_OUT);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** {@code purchasedAt}에 사서 보류가 있는 주문 (구매 상태) */
    private LectureOrder saveHeld(LocalDateTime purchasedAt) {
        Lecture lecture = lectureRepository.save(onSaleLecture());
        LectureOrder order = lectureOrderRepository.save(LectureOrderFixture.order(BUYER_ID, List.of(lecture),
                purchasedAt));
        fakeBillingClient.assumeHeld(BUYER_ID, itemId(order), PRICE_HP);
        return order;
    }

    /** {@code purchasedAt}에 사서 바로 구매 확정한 주문 */
    private LectureOrder saveConfirmed(LocalDateTime purchasedAt) {
        LectureOrder order = saveHeld(purchasedAt);
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

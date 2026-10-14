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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.ObjectionStatus;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.domain.OrderItemStatus;
import com.hapbang.lecture.fake.FakeBillingPort;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 지급 배치의 처리 메서드를 직접 호출해 DB와 billing 호출까지 확인한다(배치 빈은 모듈 테스트에서 꺼져 있다).
 * 실제 강의 Clock을 쓰므로 지급 시각이 지난 주문은 32일 전 구매로 만든다. 기한 경계는 {@code LectureOrderTest}에서 확인한다.
 * Fixture 주문에는 보류가 없으므로 {@link FakeBillingPort#assumeHeld}로 항목 가격만큼 보류가 있다고 정한다.
 */
@IntegrationTest
class OrderPayoutUseCaseTest {

    private static final Long BUYER_ID = OTHER_USER_ID;

    @Autowired
    private OrderPayoutUseCase orderPayoutUseCase;

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private FakeBillingPort fakeBillingPort;

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
        fakeBillingPort.reset();
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
        assertThat(fakeBillingPort.payouts()).containsExactly(PayoutCommand.full(BUYER_ID,
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

        assertThat(fakeBillingPort.payouts()).isEmpty();
        assertThat(items(filed)).extracting(OrderItem::getStatus).containsExactly(OrderItemStatus.CONFIRMED);
    }

    @Test
    void billing_지급이_실패하면_항목은_구매_확정으로_남는다() {
        LectureOrder failing = saveConfirmed(now().minusDays(32));
        fakeBillingPort.failPayoutFor(itemId(failing));

        orderPayoutUseCase.payOutDueItems();

        assertThat(fakeBillingPort.payouts()).isEmpty();
        assertThat(items(failing)).extracting(OrderItem::getStatus, OrderItem::getPaidOutAt)
                .containsExactly(tuple(OrderItemStatus.CONFIRMED, null));
    }

    @Test
    void 지급액이_가격과_다르면_항목_상태를_롤백한다() {
        LectureOrder order = saveConfirmed(now().minusDays(32));
        fakeBillingPort.overridePaidHp(PRICE_HP - 1);

        orderPayoutUseCase.payOutDueItems();

        assertThat(fakeBillingPort.payouts()).isEmpty();
        assertThat(items(order)).extracting(OrderItem::getStatus).containsExactly(OrderItemStatus.CONFIRMED);
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
        fakeBillingPort.failPayoutFor(itemId(failing));
        orderPayoutUseCase.payOutDueItems();
        fakeBillingPort.failPayoutFor(null);

        orderPayoutUseCase.payOutDueItems();

        assertThat(items(failing)).extracting(OrderItem::getStatus).containsExactly(OrderItemStatus.PAID_OUT);
        assertThat(fakeBillingPort.payouts()).extracting(PayoutCommand::referenceId).containsExactly(itemId(failing));
    }

    @Test
    void 조회_실패도_다음_실행에서_다시_지급한다() {
        LectureOrder failing = saveConfirmed(now().minusDays(32));
        doThrow(new IllegalStateException("처리 실패")).when(lectureOrderSupport)
                .findOrderByItemId(eq(itemId(failing)));
        orderPayoutUseCase.payOutDueItems();
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
        fakeBillingPort.assumeHeld(BUYER_ID, itemId(order), PRICE_HP);
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

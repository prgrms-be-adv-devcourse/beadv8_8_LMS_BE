package com.hapbang.lecture.app;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.ConfirmationType;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.domain.OrderItemStatus;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.shared.lecture.domain.LectureConfirmationType;
import com.hapbang.shared.lecture.event.LectureOrderItemConfirmedEvent;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 자동 구매 확정 배치의 처리 메서드를 직접 호출해 DB까지 확인한다(배치 빈은 모듈 테스트에서 꺼져 있다).
 * 실제 강의 Clock을 쓰므로 기한이 지난 주문은 9일 전 구매로 만든다. 기한 경계는 {@code LectureOrderTest}에서 확인한다.
 */
@IntegrationTest
@RecordApplicationEvents
class OrderAutoConfirmUseCaseTest {

    private static final Long BUYER_ID = OTHER_USER_ID;

    @Autowired
    private OrderAutoConfirmUseCase orderAutoConfirmUseCase;

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private LectureRepository lectureRepository;

    // 한 항목의 처리 실패를 만들기 위해 항목별 주문 조회에서 예외를 던진다
    @MockitoSpyBean
    private LectureOrderSupport lectureOrderSupport;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    @Qualifier(LectureConfig.CLOCK)
    private Clock clock;

    @Autowired
    private ApplicationEvents events;

    @Test
    void 기한이_지난_구매_항목만_자동_구매_확정한다() {
        LocalDateTime overdue = now().minusDays(9);
        LectureOrder dueOrder = save(List.of(saveOnSale(), saveOnSale()), overdue);
        LectureOrder notDue = save(List.of(saveOnSale()), now());
        LectureOrder confirmed = save(List.of(saveOnSale()), overdue);
        inTransaction(confirmed, order -> order.confirmItem(itemId(confirmed, 0), overdue.plusDays(1)));
        LectureOrder canceled = save(List.of(saveOnSale()), overdue);
        inTransaction(canceled, order -> LectureOrderFixture.cancel(order, itemId(canceled, 0), overdue.plusDays(1)));

        orderAutoConfirmUseCase.confirmDueItems();

        LocalDateTime dueAutoConfirmAt = dueOrder.getItems().getFirst().getAutoConfirmAt();
        assertThat(items(dueOrder))
                .extracting(OrderItem::getStatus, OrderItem::getConfirmationType, OrderItem::getConfirmedAt)
                .containsOnly(tuple(OrderItemStatus.CONFIRMED, ConfirmationType.AUTO, dueAutoConfirmAt));
        assertThat(items(notDue)).extracting(OrderItem::getStatus).containsExactly(OrderItemStatus.PURCHASED);
        assertThat(items(confirmed)).extracting(OrderItem::getConfirmationType)
                .containsExactly(ConfirmationType.DIRECT);
        assertThat(items(canceled)).extracting(OrderItem::getStatus).containsExactly(OrderItemStatus.CANCELED);
    }

    @Test
    void 한_항목_처리가_실패해도_다음_항목을_처리한다() {
        LectureOrder failing = save(List.of(saveOnSale()), now().minusDays(10));
        LectureOrder next = save(List.of(saveOnSale()), now().minusDays(9));
        doThrow(new IllegalStateException("처리 실패")).when(lectureOrderSupport)
                .findOrderByItemId(eq(itemId(failing, 0)));

        assertThatCode(() -> orderAutoConfirmUseCase.confirmDueItems()).doesNotThrowAnyException();

        assertThat(items(failing)).extracting(OrderItem::getStatus).containsExactly(OrderItemStatus.PURCHASED);
        assertThat(items(next)).extracting(OrderItem::getStatus).containsExactly(OrderItemStatus.CONFIRMED);
    }

    @Test
    void 실패한_항목은_다음_실행에서_확정된다() {
        LectureOrder failing = save(List.of(saveOnSale()), now().minusDays(9));
        doThrow(new IllegalStateException("처리 실패")).when(lectureOrderSupport)
                .findOrderByItemId(eq(itemId(failing, 0)));
        orderAutoConfirmUseCase.confirmDueItems();
        reset(lectureOrderSupport);

        orderAutoConfirmUseCase.confirmDueItems();

        assertThat(items(failing)).extracting(OrderItem::getStatus, OrderItem::getConfirmationType)
                .containsExactly(tuple(OrderItemStatus.CONFIRMED, ConfirmationType.AUTO));
    }

    @Test
    void 이번에_자동_확정한_항목만_확정_이벤트를_내고_조회_뒤_먼저_직접_확정된_항목은_내지_않는다() {
        LocalDateTime overdue = now().minusDays(9);
        LectureOrder due = save(List.of(saveOnSale()), overdue);
        LectureOrder confirmedFirst = save(List.of(saveOnSale()), overdue);
        // 배치가 대상을 조회한 뒤, 이 항목을 처리하기 직전에 구매자가 직접 확정한 경우
        doAnswer(invocation -> {
            inTransaction(confirmedFirst, order -> order.confirmItem(itemId(confirmedFirst, 0), now()));
            return invocation.callRealMethod();
        }).when(lectureOrderSupport).findOrderByItemId(eq(itemId(confirmedFirst, 0)));

        orderAutoConfirmUseCase.confirmDueItems();

        assertThat(items(confirmedFirst)).extracting(OrderItem::getConfirmationType)
                .containsExactly(ConfirmationType.AUTO);
        assertThat(events.stream(LectureOrderItemConfirmedEvent.class)).extracting(
                        LectureOrderItemConfirmedEvent::orderItemId, LectureOrderItemConfirmedEvent::confirmationType)
                .containsExactly(tuple(itemId(due, 0), LectureConfirmationType.AUTO));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private Lecture saveOnSale() {
        return lectureRepository.save(onSaleLecture());
    }

    private LectureOrder save(List<Lecture> lectures, LocalDateTime purchasedAt) {
        return lectureOrderRepository.save(LectureOrderFixture.order(BUYER_ID, lectures, purchasedAt));
    }

    private static Long itemId(LectureOrder order, int index) {
        return order.getItems().get(index).getId();
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

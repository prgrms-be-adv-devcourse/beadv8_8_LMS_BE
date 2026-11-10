package com.hapbang.coupon.app;

import static com.hapbang.coupon.app.CouponAppFixture.CLOCK;
import static com.hapbang.coupon.app.CouponUsageFixture.*;
import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import com.hapbang.coupon.app.dto.CouponDltLogView;
import com.hapbang.coupon.config.CouponDltProperties;
import com.hapbang.coupon.domain.CouponDltKind;
import com.hapbang.coupon.domain.CouponDltLog;
import com.hapbang.coupon.domain.CouponDltStatus;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponException;
import com.hapbang.coupon.out.CouponDltLogRepository;
import com.hapbang.shared.lecture.event.OrderItemCancelCompletedEvent;

// 주문 이벤트 DLT 재처리: 실행 전 REPLAYING 선점, 저장한 원문(JSON 왕복)으로 소비 UseCase 재실행 뒤 REPLAYED, 같은 이유의 실패는 PENDING으로
// 되돌리고 409. 선점·되돌림의 동시성 규칙은 발급 DLT 재발행과 같아 그쪽 테스트가 본다
class CouponOrderDltReplayUseCaseTest {

    private static final long DLT_ID = 7L;
    private static final OrderItemCancelCompletedEvent EVENT = OrderItemCancelCompletedEvent.of(ORDER_ID, ORDER_ITEM_ID,
            MEMBER_ID, List.of(new OrderItemCancelCompletedEvent.Usage(31L, 1L)), null, List.of(), NOW);

    private final CouponDltLogRepository dltLogRepository = mock();
    private final CouponOrderConsumerUseCase consumerUseCase = mock();
    private CouponOrderDltReplayUseCase useCase;
    private CouponDltLog dltLog;

    @BeforeEach
    void setUp() {
        useCase = new CouponOrderDltReplayUseCase(dltLogRepository, consumerUseCase, CouponDltProperties.defaults(),
                transactionManager(), CLOCK);
        dltLog = CouponDltLog.isolateOrderEvent(new CouponDltLog.Source("lecture.events.v1", 1, 9L,
                "order-item:" + ORDER_ITEM_ID), CouponDltPayload.writeOrderEvent(EVENT), "원인", NOW);
        when(dltLogRepository.findById(DLT_ID)).thenReturn(Optional.of(dltLog));
        when(dltLogRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void 선점한_뒤_저장한_원문으로_소비_UseCase를_다시_실행하고_REPLAYED로_끝낸다() {
        when(consumerUseCase.consumeWithin(any(OrderItemCancelCompletedEvent.class), anyInt())).thenAnswer(invocation -> {
            assertThat(dltLog.getStatus()).isEqualTo(CouponDltStatus.REPLAYING);
            return CouponOrderConsumerUseCase.Result.APPLIED;
        });

        CouponDltLogView view = useCase.replay(ADMIN_ID, DLT_ID);

        // 재처리 경로는 제한 시간(기본 30초)을 둔 소비로 간다
        verify(consumerUseCase).consumeWithin(EVENT, 30);
        assertThat(view.kind()).isEqualTo(CouponDltKind.ORDER);
        assertThat(view.status()).isEqualTo(CouponDltStatus.REPLAYED);
        assertThat(view.handledBy()).isEqualTo(ADMIN_ID);
    }

    @Test
    void 다시_해도_같은_이유로_실패하면_PENDING으로_되돌리고_409다() {
        when(consumerUseCase.consumeWithin(any(OrderItemCancelCompletedEvent.class), anyInt()))
                .thenThrow(new CouponOrderConsumerIsolationException("주문 항목에 이벤트의 사용 시도가 없다"));

        assertThatThrownBy(() -> useCase.replay(ADMIN_ID, DLT_ID))
                .isInstanceOfSatisfying(CouponException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CouponErrorCode.INVALID_COUPON_STATUS));
        assertThat(dltLog.getStatus()).isEqualTo(CouponDltStatus.PENDING);
        assertThat(dltLog.getHandledBy()).isNull();
    }

    @Test
    void 소비가_제한_시간을_넘기면_PENDING으로_되돌리고_409로_다시_요청하게_한다() {
        when(consumerUseCase.consumeWithin(any(OrderItemCancelCompletedEvent.class), anyInt()))
                .thenThrow(new QueryTimeoutException("행 잠금 대기 제한 시간 초과"));

        assertThatThrownBy(() -> useCase.replay(ADMIN_ID, DLT_ID))
                .isInstanceOfSatisfying(CouponException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_CONCURRENT_UPDATE));
        assertThat(dltLog.getStatus()).isEqualTo(CouponDltStatus.PENDING);
        assertThat(dltLog.getHandledBy()).isNull();
    }

    @Test
    void 원문을_주문_이벤트로_읽을_수_없으면_선점하지_않고_409다() {
        CouponDltLog unreadable = CouponDltLog.isolateOrderEvent(new CouponDltLog.Source("lecture.events.v1", 1, 10L,
                null), "{broken", "역직렬화 실패", NOW);
        when(dltLogRepository.findById(DLT_ID)).thenReturn(Optional.of(unreadable));

        assertThatThrownBy(() -> useCase.replay(ADMIN_ID, DLT_ID))
                .isInstanceOfSatisfying(CouponException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CouponErrorCode.INVALID_COUPON_STATUS));
        assertThat(unreadable.getStatus()).isEqualTo(CouponDltStatus.PENDING);
        verifyNoInteractions(consumerUseCase);
    }
}

package com.hapbang.coupon.app;

import static com.hapbang.coupon.app.CouponAppFixture.CLOCK;
import static com.hapbang.coupon.app.CouponUsageFixture.transactionManager;
import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hapbang.coupon.app.dto.CouponDltLogView;
import com.hapbang.coupon.config.CouponDltProperties;
import com.hapbang.coupon.domain.CouponDltLog;
import com.hapbang.coupon.domain.CouponDltStatus;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponException;
import com.hapbang.coupon.out.CouponDltLogRepository;

// 주문 이벤트 DLT 영구 거절: 기록만 REJECTED(사유·관리자)로 닫고 보상 결과는 남기지 않는다(이 UseCase는 Redis·쿠폰 사용 저장소를 받지 않는다).
// 이미 다시 처리한 기록은 409다. 선점 lease·동시 처리 규칙은 발급 DLT와 같은 도메인(CouponDltLog) 테스트가 본다
class CouponOrderDltRejectUseCaseTest {

    private static final long DLT_ID = 8L;

    private final CouponDltLogRepository dltLogRepository = mock();
    private CouponOrderDltRejectUseCase useCase;
    private CouponDltLog dltLog;

    @BeforeEach
    void setUp() {
        useCase = new CouponOrderDltRejectUseCase(dltLogRepository, CouponDltProperties.defaults(),
                transactionManager(), CLOCK);
        dltLog = CouponDltLog.isolateOrderEvent(new CouponDltLog.Source("lecture.events.v1", 2, 11L,
                "order-item:901"), "{}", "원인", NOW);
        when(dltLogRepository.findById(DLT_ID)).thenReturn(Optional.of(dltLog));
        when(dltLogRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void 영구_거절은_기록만_REJECTED로_닫고_보상_결과를_남기지_않는다() {
        CouponDltLogView view = useCase.reject(ADMIN_ID, DLT_ID, "강의 쪽에서 주문을 정정함");

        assertThat(view.status()).isEqualTo(CouponDltStatus.REJECTED);
        assertThat(view.rejectReason()).isEqualTo("강의 쪽에서 주문을 정정함");
        assertThat(view.handledBy()).isEqualTo(ADMIN_ID);
        assertThat(view.compensationResult()).isNull();
        verify(dltLogRepository).saveAndFlush(dltLog);
    }

    @Test
    void 이미_다시_처리한_기록은_영구_거절할_수_없다() {
        dltLog.claimReplay(ADMIN_ID, NOW, CouponDltProperties.defaults().dltReplayLease());
        dltLog.completeReplay(NOW);

        assertThatThrownBy(() -> useCase.reject(ADMIN_ID, DLT_ID, "사유"))
                .isInstanceOf(CouponException.class)
                .extracting("errorCode").isEqualTo(CouponErrorCode.COUPON_DLT_ALREADY_RESOLVED);
        assertThat(dltLog.getStatus()).isEqualTo(CouponDltStatus.REPLAYED);
        verify(dltLogRepository, never()).saveAndFlush(any());
    }
}

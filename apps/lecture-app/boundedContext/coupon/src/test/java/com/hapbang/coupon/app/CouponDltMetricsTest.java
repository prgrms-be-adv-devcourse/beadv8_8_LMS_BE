package com.hapbang.coupon.app;

import static com.hapbang.coupon.app.CouponIssueConsumerUseCaseTest.providerOf;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

import com.hapbang.coupon.out.CouponDltLogRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

// DLT 미처리 지표가 PENDING과 재발행 선점 중인 REPLAYING을 함께 세는 저장소 메서드를 읽는지 본다(세는 쿼리는 리포지토리 테스트)
class CouponDltMetricsTest {

    private final CouponDltLogRepository dltLogRepository = mock();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    void 미처리_지표는_PENDING과_REPLAYING을_함께_세고_세지_못하면_NaN이다() {
        new CouponDltMetrics(providerOf(meterRegistry), dltLogRepository);
        when(dltLogRepository.countUnresolved()).thenReturn(3L).thenThrow(new IllegalStateException("db"));

        assertThat(meterRegistry.get(CouponDltMetrics.PENDING).gauge().value()).isEqualTo(3.0);
        assertThat(meterRegistry.get(CouponDltMetrics.PENDING).gauge().value()).isNaN();
    }
}

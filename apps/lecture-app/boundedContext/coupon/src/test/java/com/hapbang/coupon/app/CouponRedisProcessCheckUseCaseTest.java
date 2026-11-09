package com.hapbang.coupon.app;

import static com.hapbang.coupon.app.CouponAppFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.hapbang.coupon.app.port.CouponIssueStoreUnavailableException;
import com.hapbang.coupon.app.port.CouponRecoveryStore;
import com.hapbang.coupon.app.port.CouponRecoveryStore.ProcessCheck;
import com.hapbang.coupon.out.RecoveryLookupRepository;
import com.hapbang.coupon.out.redis.CouponRedisProperties;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

// 점검 대상마다 Redis 점검을 부르고 결과를 지표로 남기는지, 한 쿠폰의 실패가 회차를 멈추지 않는지 본다. 판정 자체는 RecoveryLuaTest가 본다
class CouponRedisProcessCheckUseCaseTest {

    private final RecoveryLookupRepository lookupRepository = mock();
    private final CouponRecoveryStore recoveryStore = mock();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final CouponRedisProcessCheckUseCase useCase;

    @SuppressWarnings("unchecked")
    CouponRedisProcessCheckUseCaseTest() {
        ObjectProvider<MeterRegistry> provider = mock();
        when(provider.getIfAvailable(any())).thenReturn(meterRegistry);
        useCase = new CouponRedisProcessCheckUseCase(lookupRepository, recoveryStore, new CouponRedisProperties(null),
                provider, CLOCK);
    }

    private double count(String result) {
        return meterRegistry.counter(CouponRedisProcessCheckUseCase.METRIC, "result", result).count();
    }

    @Test
    void 프로세스가_바뀐_쿠폰은_막고_유실된_쿠폰은_알리며_한_쿠폰의_실패는_다음_쿠폰을_막지_않는다() {
        when(lookupRepository.findRedisReadyCouponIds(anyCollection(), any())).thenReturn(List.of(1L, 2L, 3L, 4L, 5L));
        when(recoveryStore.checkProcess(1L)).thenReturn(ProcessCheck.BLOCKED);
        when(recoveryStore.checkProcess(2L)).thenThrow(new CouponIssueStoreUnavailableException("연결 실패", null));
        when(recoveryStore.checkProcess(3L)).thenReturn(ProcessCheck.MISSING);
        when(recoveryStore.checkProcess(4L)).thenReturn(ProcessCheck.SAME);
        when(recoveryStore.checkProcess(5L)).thenReturn(ProcessCheck.ALREADY_BLOCKED);

        assertThat(useCase.checkAll()).isEqualTo(1);

        verify(recoveryStore, times(5)).checkProcess(anyLong());
        assertThat(count("blocked")).isEqualTo(1.0);
        assertThat(count("missing")).isEqualTo(1.0);
        assertThat(count("adopted")).isZero();
    }
}

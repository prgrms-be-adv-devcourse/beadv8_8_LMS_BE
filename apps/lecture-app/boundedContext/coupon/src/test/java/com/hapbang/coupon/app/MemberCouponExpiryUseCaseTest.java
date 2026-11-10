package com.hapbang.coupon.app;

import static com.hapbang.coupon.app.CouponAppFixture.CLOCK;
import static com.hapbang.coupon.app.CouponIssueConsumerUseCaseTest.providerOf;
import static com.hapbang.coupon.app.CouponUsageFixture.transactionManager;
import static com.hapbang.coupon.domain.CouponFixture.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.PlatformTransactionManager;

import com.hapbang.coupon.config.CouponExpiryProperties;
import com.hapbang.coupon.out.MemberCouponRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

// 만료 배치 서비스: 회차 시작 시각 하나로 묶음을 반복하고, 묶음 크기보다 적게 고르거나 하나도 못 바꾸면 끝낸다. 묶음마다 트랜잭션이 따로다.
// 대상 조건(AVAILABLE·기한 지남·판매자 쿠폰만, RESERVED·미래 만료·플랫폼 불변)은 저장소 테스트가 실제 DB로 본다
class MemberCouponExpiryUseCaseTest {

    private final MemberCouponRepository memberCouponRepository = mock();
    private final PlatformTransactionManager transactionManager = transactionManager();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final MemberCouponExpiryUseCase useCase = new MemberCouponExpiryUseCase(memberCouponRepository,
            new CouponExpiryProperties(null, 2), providerOf(meterRegistry), transactionManager, CLOCK);

    @Test
    void 묶음_크기만큼_찬_동안_같은_시각으로_반복하고_모자란_묶음에서_끝낸다() {
        when(memberCouponRepository.findExpiredSellerCouponIds(NOW, Limit.of(2)))
                .thenReturn(List.of(1L, 2L), List.of(3L, 4L), List.of(5L));
        when(memberCouponRepository.expire(List.of(1L, 2L), NOW)).thenReturn(2);
        when(memberCouponRepository.expire(List.of(3L, 4L), NOW)).thenReturn(1);
        when(memberCouponRepository.expire(List.of(5L), NOW)).thenReturn(1);

        MemberCouponExpiryUseCase.ExpiryRun run = useCase.expireDue();

        assertThat(run.expired()).isEqualTo(4);
        assertThat(run.batches()).isEqualTo(3);
        assertThat(meterRegistry.counter(MemberCouponExpiryUseCase.EXPIRED_METRIC).count()).isEqualTo(4);
        // 묶음마다 짧은 트랜잭션
        verify(transactionManager, times(3)).commit(any());
    }

    @Test
    void 대상이_없으면_갱신하지_않고_한_묶음으로_끝낸다() {
        when(memberCouponRepository.findExpiredSellerCouponIds(NOW, Limit.of(2))).thenReturn(List.of());

        MemberCouponExpiryUseCase.ExpiryRun run = useCase.expireDue();

        assertThat(run.expired()).isZero();
        assertThat(run.batches()).isEqualTo(1);
        verify(memberCouponRepository, never()).expire(any(), any());
    }

    @Test
    void 가득_찬_묶음에서_하나도_못_바꾸면_같은_대상을_다시_고르지_않게_끝낸다() {
        when(memberCouponRepository.findExpiredSellerCouponIds(NOW, Limit.of(2))).thenReturn(List.of(1L, 2L));
        when(memberCouponRepository.expire(List.of(1L, 2L), NOW)).thenReturn(0);

        assertThat(useCase.expireDue().batches()).isEqualTo(1);
    }
}

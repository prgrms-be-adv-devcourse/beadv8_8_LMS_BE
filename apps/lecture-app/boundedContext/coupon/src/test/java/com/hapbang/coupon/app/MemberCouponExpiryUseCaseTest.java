package com.hapbang.coupon.app;

import static com.hapbang.coupon.app.CouponAppFixture.CLOCK;
import static com.hapbang.coupon.app.CouponIssueConsumerUseCaseTest.providerOf;
import static com.hapbang.coupon.app.CouponUsageFixture.transactionManager;
import static com.hapbang.coupon.domain.CouponFixture.NOW;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;

import com.hapbang.coupon.config.CouponExpiryProperties;
import com.hapbang.coupon.out.MemberCouponRepository;
import com.hapbang.coupon.out.MemberCouponExpiryTarget;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

// 만료 배치 서비스: 회차 시작 시각 하나와 expiresAt/id cursor로 조회하며, 실패 건은 ID별 트랜잭션으로 격리한다.
// 대상 조건(판매자·플랫폼의 AVAILABLE·기한 지남, RESERVED·미래 만료 불변)은 저장소 테스트가 실제 DB로 본다
class MemberCouponExpiryUseCaseTest {

    private final MemberCouponRepository memberCouponRepository = mock();
    private final CouponBudgetReleaseRecorder recorder = mock();
    private final PlatformTransactionManager transactionManager = transactionManager();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final MemberCouponExpiryUseCase useCase = new MemberCouponExpiryUseCase(recorder, memberCouponRepository,
            new CouponExpiryProperties(null, 2), providerOf(meterRegistry), transactionManager, CLOCK);

    @Test
    void 묶음_크기만큼_찬_동안_같은_시각으로_반복하고_모자란_묶음에서_끝낸다() {
        page(0, 1, 2);
        page(2, 3, 4);
        page(4, 5);
        when(memberCouponRepository.expire(List.of(1L, 2L), NOW)).thenReturn(2);
        when(memberCouponRepository.expire(List.of(3L, 4L), NOW)).thenReturn(1);
        when(memberCouponRepository.expire(List.of(5L), NOW)).thenReturn(1);

        MemberCouponExpiryUseCase.ExpiryRun run = useCase.expireDue();

        assertThat(run.expired()).isEqualTo(4);
        assertThat(run.batches()).isEqualTo(3);
        assertThat(meterRegistry.counter(MemberCouponExpiryUseCase.EXPIRED_METRIC).count()).isEqualTo(4);
        // 조회와 만료·해제 등록을 각각 짧은 트랜잭션으로 실행한다.
        verify(transactionManager, times(6)).commit(any());
    }

    @Test
    void 대상이_없으면_갱신하지_않고_한_묶음으로_끝낸다() {
        page(0);

        MemberCouponExpiryUseCase.ExpiryRun run = useCase.expireDue();

        assertThat(run.expired()).isZero();
        assertThat(run.batches()).isEqualTo(1);
        verify(memberCouponRepository, never()).expire(any(), any());
    }

    @Test
    void 하나도_못_바꿔도_cursor를_진행하여_같은_대상을_다시_고르지_않는다() {
        page(0, 1, 2);
        page(2);
        when(memberCouponRepository.expire(List.of(1L, 2L), NOW)).thenReturn(0);

        assertThat(useCase.expireDue().batches()).isEqualTo(2);
        verify(memberCouponRepository).findExpiredCouponTargets(NOW, NOW, 2, Limit.of(2));
    }

    @Test
    void 해제_등록이_실패한_묶음은_ID별로_격리하고_다음_cursor의_정상_쿠폰도_만료한다() {
        page(0, 1, 2);
        page(2, 3);
        when(memberCouponRepository.expire(List.of(1L, 2L), NOW)).thenReturn(2);
        when(memberCouponRepository.expire(List.of(1L), NOW)).thenReturn(1);
        when(memberCouponRepository.expire(List.of(2L), NOW)).thenReturn(1);
        when(memberCouponRepository.expire(List.of(3L), NOW)).thenReturn(1);
        doThrow(new IllegalArgumentException("예약 ID 누락")).when(recorder).recordExpired(1, NOW);

        MemberCouponExpiryUseCase.ExpiryRun run = useCase.expireDue();

        assertThat(run.expired()).isEqualTo(2);
        assertThat(run.batches()).isEqualTo(2);
        verify(transactionManager, times(2)).rollback(any());
        verify(recorder).recordExpired(2, NOW);
        verify(recorder).recordExpired(3, NOW);
        verify(memberCouponRepository, times(1)).findExpiredCouponTargets(NOW, null, 0, Limit.of(2));
    }

    @Test
    void DB_연결_장애는_ID별로_반복하지_않고_회차를_중단한다() {
        page(0, 1, 2);
        when(memberCouponRepository.expire(List.of(1L, 2L), NOW))
                .thenThrow(new DataAccessResourceFailureException("DB 연결 실패"));

        assertThatThrownBy(useCase::expireDue).isInstanceOf(DataAccessResourceFailureException.class);

        verify(memberCouponRepository, times(1)).expire(any(), any());
        verifyNoInteractions(recorder);
    }

    @Test
    void 트랜잭션_생성_장애도_회차를_중단한다() {
        doThrow(new CannotCreateTransactionException("DB 연결 실패")).when(transactionManager).getTransaction(any());

        assertThatThrownBy(useCase::expireDue).isInstanceOf(CannotCreateTransactionException.class);

        verifyNoInteractions(memberCouponRepository, recorder);
    }

    private void page(long afterId, long... ids) {
        List<MemberCouponExpiryTarget> targets = java.util.Arrays.stream(ids)
                .mapToObj(id -> new MemberCouponExpiryTarget(id, NOW)).toList();
        when(memberCouponRepository.findExpiredCouponTargets(NOW, afterId == 0 ? null : NOW, afterId, Limit.of(2)))
                .thenReturn(targets);
    }
}

package com.hapbang.coupon.app;

import static com.hapbang.coupon.app.CouponAppFixture.*;
import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.hapbang.coupon.app.dto.CouponDltLogView;
import com.hapbang.coupon.app.port.CouponIssueEventPublisher;
import com.hapbang.coupon.app.port.CouponIssueEventPublisher.PublishResult;
import com.hapbang.coupon.config.CouponDltProperties;
import com.hapbang.coupon.config.CouponIssueDeliveryProperties;
import com.hapbang.coupon.domain.CouponDltLog;
import com.hapbang.coupon.domain.CouponDltStatus;
import com.hapbang.coupon.domain.RecoveryRun;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponException;
import com.hapbang.coupon.out.CouponDltLogRepository;
import com.hapbang.coupon.out.RecoveryRunRepository;
import com.hapbang.shared.coupon.event.CouponIssueRequested;

// 재발행 분기: 전송 전 REPLAYING 선점, ACK 뒤 REPLAYED, 전송 실패 되돌림(새 PENDING이 있으면 REPLAYED로 끝냄), 선점 lease 회수,
// 복구 중 거절, 동시 처리.
// 선점 뒤 재격리가 새 PENDING이 되는지(부분 유일 인덱스)는 리포지토리 테스트가 본다
class CouponDltReplayUseCaseTest {

    private static final long DLT_ID = 5L;
    private static final CouponIssueRequested EVENT = new CouponIssueRequested("req-1", COUPON_ID, MEMBER_ID, 1, NOW,
            NOW.plus(Duration.ofDays(30)));

    private final CouponDltLogRepository dltLogRepository = mock();
    private final RecoveryRunRepository recoveryRunRepository = mock();
    private final CouponIssueEventPublisher publisher = mock();
    private final PlatformTransactionManager transactionManager = mock();
    private final CouponIssueDeliveryProperties properties = CouponIssueDeliveryProperties.defaults();
    private final CouponDltProperties dltProperties = CouponDltProperties.defaults();
    private final Duration lease = dltProperties.dltReplayLease();

    private CouponDltReplayUseCase useCase;
    private CouponDltLog dltLog;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        useCase = new CouponDltReplayUseCase(dltLogRepository, recoveryRunRepository, publisher, properties,
                dltProperties, transactionManager, CLOCK);
        dltLog = CouponDltLog.isolate(new CouponDltLog.Source("coupon.events.v1", 0, 3L, String.valueOf(COUPON_ID)),
                COUPON_ID, "req-1", CouponDltPayload.write(EVENT), "원인", NOW);
        when(dltLogRepository.findById(DLT_ID)).thenReturn(Optional.of(dltLog));
        when(dltLogRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(recoveryRunRepository.findFirstByCouponIdAndStepNotIn(anyLong(), any())).thenReturn(Optional.empty());
    }

    @Test
    void 전송_전에_REPLAYING으로_선점해_커밋하고_원문의_같은_요청을_보낸_뒤_ACK를_받으면_REPLAYED다() {
        when(publisher.publish(any(), any())).thenAnswer(invocation -> {
            assertThat(dltLog.getStatus()).isEqualTo(CouponDltStatus.REPLAYING);
            return PublishResult.ACKED;
        });

        CouponDltLogView view = useCase.replay(ADMIN_ID, DLT_ID);

        InOrder order = inOrder(dltLogRepository, transactionManager, publisher);
        order.verify(dltLogRepository).saveAndFlush(dltLog);
        order.verify(transactionManager).commit(any());
        order.verify(publisher).publish(EVENT, properties.republish().ackTimeout());
        order.verify(dltLogRepository).saveAndFlush(dltLog);
        assertThat(view.status()).isEqualTo(CouponDltStatus.REPLAYED);
        assertThat(view.handledBy()).isEqualTo(ADMIN_ID);
        assertThat(view.replayClaimedAt()).isEqualTo(NOW);
    }

    @Test
    void 선점_lease_안의_REPLAYING은_다시_보내지_않는다() {
        dltLog.claimReplay(OTHER_MEMBER_ID, NOW.minus(lease).plusSeconds(1), lease);

        assertThatThrownBy(() -> useCase.replay(ADMIN_ID, DLT_ID))
                .isInstanceOf(CouponException.class)
                .extracting("errorCode").isEqualTo(CouponErrorCode.COUPON_DLT_ALREADY_RESOLVED);
        verifyNoInteractions(publisher);
    }

    @Test
    void 선점한_채_멈춰_lease가_지난_REPLAYING은_다시_선점해_보낸다() {
        dltLog.claimReplay(OTHER_MEMBER_ID, NOW.minus(lease), lease);
        when(publisher.publish(any(), any())).thenReturn(PublishResult.ACKED);

        CouponDltLogView view = useCase.replay(ADMIN_ID, DLT_ID);

        verify(publisher).publish(EVENT, properties.republish().ackTimeout());
        assertThat(view.status()).isEqualTo(CouponDltStatus.REPLAYED);
        assertThat(view.handledBy()).isEqualTo(ADMIN_ID);
        assertThat(view.replayClaimedAt()).isEqualTo(NOW);
    }

    @Test
    void ACK_뒤_다른_요청이_lease가_지나_다시_선점했으면_결과를_덮지_않는다() {
        CouponDltLog reclaimed = mock();
        when(reclaimed.getVersion()).thenReturn(7L);
        when(dltLogRepository.findById(DLT_ID)).thenReturn(Optional.of(dltLog), Optional.of(reclaimed));
        when(publisher.publish(any(), any())).thenReturn(PublishResult.ACKED);

        CouponDltLogView view = useCase.replay(ADMIN_ID, DLT_ID);

        verify(reclaimed, never()).completeReplay(any());
        assertThat(view.status()).isEqualTo(CouponDltStatus.REPLAYING);
    }

    @Test
    void 보내지_못하면_PENDING으로_되돌리고_선점_시각을_지우며_503이다() {
        when(publisher.publish(any(), any())).thenReturn(PublishResult.TIMED_OUT);
        when(dltLogRepository.existsPendingFor(COUPON_ID, "req-1")).thenReturn(false);

        assertThatThrownBy(() -> useCase.replay(ADMIN_ID, DLT_ID))
                .isInstanceOf(CouponException.class)
                .extracting("errorCode").isEqualTo(CouponErrorCode.COUPON_EVENT_PUBLISH_FAILED);
        assertThat(dltLog.isPending()).isTrue();
        assertThat(dltLog.getHandledBy()).isNull();
        assertThat(dltLog.getReplayClaimedAt()).isNull();
    }

    @Test
    void 보내지_못했어도_그사이_같은_요청이_다시_격리되었으면_되돌리지_않고_REPLAYED로_끝낸다() {
        when(publisher.publish(any(), any())).thenReturn(PublishResult.TIMED_OUT);
        when(dltLogRepository.existsPendingFor(COUPON_ID, "req-1")).thenReturn(true);

        assertThatThrownBy(() -> useCase.replay(ADMIN_ID, DLT_ID))
                .isInstanceOf(CouponException.class)
                .extracting("errorCode").isEqualTo(CouponErrorCode.COUPON_EVENT_PUBLISH_FAILED);
        assertThat(dltLog.getStatus()).isEqualTo(CouponDltStatus.REPLAYED);
    }

    @Test
    void 쿠폰의_복구가_끝나지_않았으면_선점하지도_보내지도_않는다() {
        when(recoveryRunRepository.findFirstByCouponIdAndStepNotIn(eq(COUPON_ID), any()))
                .thenReturn(Optional.of(mock(RecoveryRun.class)));

        assertThatThrownBy(() -> useCase.replay(ADMIN_ID, DLT_ID))
                .isInstanceOf(CouponException.class)
                .extracting("errorCode").isEqualTo(CouponErrorCode.COUPON_RECOVERY_IN_PROGRESS);
        assertThat(dltLog.isPending()).isTrue();
        verifyNoInteractions(publisher);
    }

    @Test
    void 이미_처리한_기록은_다시_보내지_않는다() {
        dltLog.reject(ADMIN_ID, "사유", NOW, lease);

        assertThatThrownBy(() -> useCase.replay(ADMIN_ID, DLT_ID))
                .isInstanceOf(CouponException.class)
                .extracting("errorCode").isEqualTo(CouponErrorCode.COUPON_DLT_ALREADY_RESOLVED);
        verifyNoInteractions(publisher);
    }

    @Test
    void 다른_관리자가_동시에_먼저_처리하면_보내지_않고_이미_처리한_기록이다() {
        when(dltLogRepository.saveAndFlush(any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(CouponDltLog.class, DLT_ID));

        assertThatThrownBy(() -> useCase.replay(ADMIN_ID, DLT_ID))
                .isInstanceOf(CouponException.class)
                .extracting("errorCode").isEqualTo(CouponErrorCode.COUPON_DLT_ALREADY_RESOLVED);
        verifyNoInteractions(publisher);
    }
}

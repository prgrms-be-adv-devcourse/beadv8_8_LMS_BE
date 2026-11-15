package com.hapbang.coupon.app;

import static com.hapbang.coupon.app.CouponAppFixture.*;
import static com.hapbang.coupon.app.CouponIssueConsumerUseCaseTest.providerOf;
import static com.hapbang.coupon.domain.CouponFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.hapbang.coupon.app.dto.CouponDltLogView;
import com.hapbang.coupon.app.port.CouponCompensationStore;
import com.hapbang.coupon.app.port.CouponIssueStoreUnavailableException;
import com.hapbang.coupon.config.CouponDltProperties;
import com.hapbang.coupon.domain.CouponDltLog;
import com.hapbang.coupon.domain.CouponDltStatus;
import com.hapbang.coupon.domain.CouponIssueRequest;
import com.hapbang.coupon.domain.CouponIssueResult;
import com.hapbang.coupon.domain.IssueCompensation;
import com.hapbang.coupon.domain.IssueDelivery;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponException;
import com.hapbang.coupon.out.CouponDltLogRepository;
import com.hapbang.coupon.out.CouponIssueRequestRepository;
import com.hapbang.coupon.out.CouponRepository;
import com.hapbang.coupon.out.ProcessedOperationRepository;
import com.hapbang.shared.coupon.event.CouponIssueRequested;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

// 영구 거절의 순서(DB 거절 확정 → Redis 보상 → 보상 결과 기록)와 재요청·거절할 수 없는 경우. 보상 표식·epoch 규칙 자체는 compensate.lua
// 테스트, 재거절해도 재고가 그대로인 것은 e2e가 본다
class CouponDltRejectUseCaseTest {

    private static final long DLT_ID = 5L;
    private static final Duration LEASE = CouponDltProperties.defaults().dltReplayLease();
    private static final String REQUEST_ID = "req-1";
    private static final Instant ISSUED_AT = NOW.minusSeconds(60);
    private static final CouponIssueRequested EVENT = new CouponIssueRequested(REQUEST_ID, COUPON_ID, MEMBER_ID, 1,
            ISSUED_AT, ISSUED_AT.plus(Duration.ofDays(30)));

    private final CouponDltLogRepository dltLogRepository = mock();
    private final CouponIssueRequestRepository issueRequestRepository = mock();
    private final ProcessedOperationRepository processedOperationRepository = mock();
    private final CouponRepository couponRepository = mock();
    private final CouponCompensationStore compensationStore = mock();
    private final PlatformTransactionManager transactionManager = mock();

    private CouponDltRejectUseCase useCase;
    private CouponDltLog dltLog;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        CouponDltMetrics metrics = new CouponDltMetrics(providerOf(new SimpleMeterRegistry()), dltLogRepository);
        useCase = new CouponDltRejectUseCase(dltLogRepository, issueRequestRepository, processedOperationRepository,
                couponRepository, compensationStore, metrics, CouponDltProperties.defaults(), transactionManager, CLOCK);
        dltLog = log(CouponDltPayload.write(EVENT));
        when(dltLogRepository.findById(DLT_ID)).thenReturn(Optional.of(dltLog));
        when(couponRepository.existsById(COUPON_ID)).thenReturn(true);
        when(issueRequestRepository.findByCouponIdAndIssueRequestId(COUPON_ID, REQUEST_ID)).thenReturn(Optional.empty());
        when(processedOperationRepository.findByOperationId(anyString())).thenReturn(Optional.empty());
        when(compensationStore.compensate(anyLong(), anyString(), anyLong(), anyLong()))
                .thenReturn(IssueCompensation.RESTORED);
    }

    /** 저장한 발급 결과를 같은 (쿠폰, 요청 ID) 조회가 돌려주게 한다 */
    private void storeSavedResult(CouponIssueRequest[] saved) {
        when(issueRequestRepository.save(any())).thenAnswer(invocation -> {
            saved[0] = invocation.getArgument(0);
            return saved[0];
        });
        when(issueRequestRepository.findByCouponIdAndIssueRequestId(COUPON_ID, REQUEST_ID))
                .thenAnswer(invocation -> Optional.ofNullable(saved[0]));
    }

    private static CouponDltLog log(String payload) {
        return CouponDltLog.isolate(new CouponDltLog.Source("coupon.events.v1", 0, 3L, String.valueOf(COUPON_ID)),
                COUPON_ID, REQUEST_ID, payload, "원인", NOW);
    }

    @Test
    void 발급_결과를_거절로_먼저_확정한_뒤_이벤트의_회원_epoch로_보상하고_결과를_남긴다() {
        CouponIssueRequest[] saved = new CouponIssueRequest[1];
        storeSavedResult(saved);

        CouponDltLogView view = useCase.reject(ADMIN_ID, DLT_ID, "원인 확인 후 거절");

        var order = inOrder(issueRequestRepository, transactionManager, compensationStore);
        order.verify(issueRequestRepository).save(any());
        order.verify(transactionManager).commit(any());
        order.verify(compensationStore).compensate(COUPON_ID, REQUEST_ID, MEMBER_ID, 1);
        assertThat(saved[0].getResult()).isEqualTo(CouponIssueResult.REJECTED);
        assertThat(saved[0].getRejectReason()).isEqualTo("원인 확인 후 거절");
        assertThat(saved[0].getCompensationStatus()).isEqualTo("RESTORED");
        verify(processedOperationRepository).save(any());
        assertThat(view.status()).isEqualTo(CouponDltStatus.REJECTED);
        assertThat(view.compensationResult()).isEqualTo(IssueCompensation.RESTORED);
        assertThat(view.handledBy()).isEqualTo(ADMIN_ID);
    }

    @Test
    void Redis에_닿지_못하면_거절은_확정된_채_503이고_다시_요청하면_보상만_다시_한다() {
        CouponIssueRequest[] saved = new CouponIssueRequest[1];
        storeSavedResult(saved);
        when(compensationStore.compensate(anyLong(), anyString(), anyLong(), anyLong()))
                .thenThrow(new CouponIssueStoreUnavailableException("down", null))
                .thenReturn(IssueCompensation.ALREADY_COMPENSATED);

        assertThatThrownBy(() -> useCase.reject(ADMIN_ID, DLT_ID, "사유"))
                .isInstanceOf(CouponIssueStoreUnavailableException.class);
        assertThat(dltLog.getStatus()).isEqualTo(CouponDltStatus.REJECTED);
        assertThat(dltLog.getCompensationResult()).isNull();

        CouponDltLogView view = useCase.reject(ADMIN_ID, DLT_ID, "사유");

        verify(issueRequestRepository, times(1)).save(any());
        verify(compensationStore, times(2)).compensate(COUPON_ID, REQUEST_ID, MEMBER_ID, 1);
        assertThat(view.compensationResult()).isEqualTo(IssueCompensation.ALREADY_COMPENSATED);
    }

    @Test
    void 이미_발급이_확정된_요청은_거절하지_않고_보상도_하지_않는다() {
        when(issueRequestRepository.findByCouponIdAndIssueRequestId(COUPON_ID, REQUEST_ID))
                .thenReturn(Optional.of(new IssueDelivery(REQUEST_ID, COUPON_ID, MEMBER_ID, 1, EVENT.issuedAt(),
                        EVENT.expiresAt()).toIssued(9L, NOW)));

        assertThatThrownBy(() -> useCase.reject(ADMIN_ID, DLT_ID, "사유"))
                .isInstanceOf(CouponException.class)
                .extracting("errorCode").isEqualTo(CouponErrorCode.COUPON_DLT_ALREADY_RESOLVED);
        verify(transactionManager).rollback(any());
        verifyNoInteractions(compensationStore);
    }

    @Test
    void 재발행한_기록과_선점_lease_안의_REPLAYING은_거절하지_않는다() {
        dltLog.claimReplay(ADMIN_ID, NOW.minus(LEASE).plusSeconds(1), LEASE);

        assertThatThrownBy(() -> useCase.reject(ADMIN_ID, DLT_ID, "사유"))
                .isInstanceOf(CouponException.class)
                .extracting("errorCode").isEqualTo(CouponErrorCode.COUPON_DLT_ALREADY_RESOLVED);

        dltLog.completeReplay(NOW);

        assertThatThrownBy(() -> useCase.reject(ADMIN_ID, DLT_ID, "사유"))
                .isInstanceOf(CouponException.class)
                .extracting("errorCode").isEqualTo(CouponErrorCode.COUPON_DLT_ALREADY_RESOLVED);
        verifyNoInteractions(compensationStore);
    }

    @Test
    void 선점한_채_멈춰_lease가_지난_REPLAYING은_거절하고_보상한다() {
        dltLog.claimReplay(OTHER_MEMBER_ID, NOW.minus(LEASE), LEASE);

        CouponDltLogView view = useCase.reject(ADMIN_ID, DLT_ID, "멈춘 재발행 거절");

        assertThat(view.status()).isEqualTo(CouponDltStatus.REJECTED);
        verify(compensationStore).compensate(COUPON_ID, REQUEST_ID, MEMBER_ID, 1);
    }

    @Test
    void 원문을_발급_요청으로_읽을_수_없으면_기록만_닫고_보상하지_않는다() {
        dltLog = log("not-json");
        when(dltLogRepository.findById(DLT_ID)).thenReturn(Optional.of(dltLog));

        CouponDltLogView view = useCase.reject(ADMIN_ID, DLT_ID, "깨진 메시지");

        assertThat(view.status()).isEqualTo(CouponDltStatus.REJECTED);
        verify(issueRequestRepository, never()).save(any());
        verifyNoInteractions(compensationStore);
    }

    @Test
    void 없는_기록은_404다() {
        when(dltLogRepository.findById(DLT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.reject(ADMIN_ID, DLT_ID, "사유"))
                .isInstanceOf(CouponException.class)
                .extracting("errorCode").isEqualTo(CouponErrorCode.COUPON_DLT_NOT_FOUND);
    }

    @Test
    void 보상할_접수_기록이_없으면_거절은_유지하고_결과를_그대로_남긴다() {
        when(compensationStore.compensate(anyLong(), anyString(), anyLong(), anyLong()))
                .thenReturn(IssueCompensation.NO_RECORD);
        ArgumentCaptor<CouponIssueRequest> saved = ArgumentCaptor.forClass(CouponIssueRequest.class);
        when(issueRequestRepository.save(saved.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        CouponDltLogView view = useCase.reject(ADMIN_ID, DLT_ID, "사유");

        assertThat(view.status()).isEqualTo(CouponDltStatus.REJECTED);
        assertThat(view.compensationResult()).isEqualTo(IssueCompensation.NO_RECORD);
    }
}

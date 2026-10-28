package com.hapbang.billing.app;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.hapbang.billing.domain.PointRefundRequestStatus;
import com.hapbang.billing.out.PaymentKeyCipher;
import com.hapbang.billing.out.PointRefundRequestRepository;
import com.hapbang.billing.out.TossPaymentsClient;

import lombok.extern.slf4j.Slf4j;

// 끝나지 않은 환불 요청(REQUESTED·PROCESSING)을 이어서 처리한다 (#260). 결과를 기다리는 충전 건(PROCESSING·UNKNOWN)은 결제 조회
// (취소 이력)로 확정하고, 우리 취소가 없으면 같은 Idempotency-Key로 다시 요청한다. 접수 후 처리 전에 멈춘 요청도 이어서 처리한다.
// 방금 처리 중인 요청·충전 건과 겹치지 않도록 마지막 변경 후 일정 시간(기본 1분)이 지난 것만 처리한다.
// 요청마다 따로 처리하고 예외는 요청별로 잡아 다음 요청으로 넘어간다. 운영자 확인(MANUAL_REVIEW_REQUIRED) 요청은 다루지 않는다.
@Slf4j
@Service
public class RefundRecoveryUseCase {

    static final int BATCH_SIZE = 100;
    static final Set<PointRefundRequestStatus> OPEN_STATUSES =
            EnumSet.of(PointRefundRequestStatus.REQUESTED, PointRefundRequestStatus.PROCESSING);

    private final PointRefundRequestRepository pointRefundRequestRepository;
    private final RefundProcessUseCase refundProcessUseCase;
    private final TossPaymentsClient tossPaymentsClient;
    private final PaymentKeyCipher paymentKeyCipher;
    private final Duration minimumAge;

    public RefundRecoveryUseCase(PointRefundRequestRepository pointRefundRequestRepository,
            RefundProcessUseCase refundProcessUseCase, TossPaymentsClient tossPaymentsClient,
            PaymentKeyCipher paymentKeyCipher,
            @Value("${hapbang.billing.refund.recovery-after:PT1M}") Duration minimumAge) {
        this.pointRefundRequestRepository = pointRefundRequestRepository;
        this.refundProcessUseCase = refundProcessUseCase;
        this.tossPaymentsClient = tossPaymentsClient;
        this.paymentKeyCipher = paymentKeyCipher;
        this.minimumAge = minimumAge;
    }

    // 기준 시각에서 minimumAge 이전에 마지막으로 바뀐 끝나지 않은 요청을 오래된 순으로 최대 BATCH_SIZE건 처리하고, 처리한 건수를 돌려준다.
    public int recoverStale(Instant now) {
        if (!tossPaymentsClient.isConfigured() || !paymentKeyCipher.isConfigured()) {
            return 0;
        }
        Instant staleBefore = now.minus(minimumAge);
        List<Long> requestIds = pointRefundRequestRepository.findIdsByStatusInUpdatedBefore(OPEN_STATUSES,
                staleBefore, PageRequest.of(0, BATCH_SIZE));
        int unresolved = 0;
        for (Long requestId : requestIds) {
            try {
                // 이번 실행 뒤에도 자동 처리 대상(REQUESTED·PROCESSING)으로 남은 요청만 센다
                if (OPEN_STATUSES.contains(refundProcessUseCase.recover(requestId, staleBefore).status())) {
                    unresolved++;
                }
            } catch (RuntimeException exception) {
                unresolved++;
                log.warn("환불 결과 확인 실패, 다음 실행에서 다시 확인한다 refundRequestId={}", requestId, exception);
            }
        }
        if (!requestIds.isEmpty()) {
            log.info("환불 결과 확인 대상 {}건, 아직 처리 중인 요청 {}건", requestIds.size(), unresolved);
        }
        return requestIds.size();
    }
}

package com.hapbang.billing.app;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.hapbang.billing.domain.PointChargeStatus;
import com.hapbang.billing.out.PaymentKeyCipher;
import com.hapbang.billing.out.PointChargeRepository;
import com.hapbang.billing.out.TossPaymentsClient;

import lombok.extern.slf4j.Slf4j;

// 결과를 확정하지 못한 충전 건(PROCESSING)을 결제 조회로 다시 확정한다 (#224).
// 회원이 재요청하지 않아도(응답을 못 받고 화면을 닫음 등) 승인된 결제가 충전되거나 실패로 끝나게 한다.
// 방금 승인 요청을 시작한 건과 겹치지 않도록 마지막 변경 후 일정 시간(기본 1분)이 지난 건만 처리한다.
// 건마다 따로 처리하고 예외는 건별로 잡아 다음 건으로 넘어간다(한 건의 실패가 다른 건을 막지 않는다).
@Slf4j
@Service
public class ChargeRecoveryUseCase {

    static final int BATCH_SIZE = 100;

    private final PointChargeRepository pointChargeRepository;
    private final ChargeConfirmUseCase chargeConfirmUseCase;
    private final TossPaymentsClient tossPaymentsClient;
    private final PaymentKeyCipher paymentKeyCipher;
    private final Duration minimumAge;

    public ChargeRecoveryUseCase(PointChargeRepository pointChargeRepository,
            ChargeConfirmUseCase chargeConfirmUseCase, TossPaymentsClient tossPaymentsClient,
            PaymentKeyCipher paymentKeyCipher,
            @Value("${hapbang.billing.charge.recovery-after:PT1M}") Duration minimumAge) {
        this.pointChargeRepository = pointChargeRepository;
        this.chargeConfirmUseCase = chargeConfirmUseCase;
        this.tossPaymentsClient = tossPaymentsClient;
        this.paymentKeyCipher = paymentKeyCipher;
        this.minimumAge = minimumAge;
    }

    // 기준 시각에서 minimumAge 이전에 마지막으로 바뀐 PROCESSING 건을 오래된 순으로 최대 BATCH_SIZE건 확인하고, 확인한 건수를 돌려준다.
    public int recoverStale(Instant now) {
        if (!tossPaymentsClient.isConfigured() || !paymentKeyCipher.isConfigured()) {
            return 0;
        }
        List<Long> chargeIds = pointChargeRepository.findIdsByStatusUpdatedBefore(PointChargeStatus.PROCESSING,
                now.minus(minimumAge), PageRequest.of(0, BATCH_SIZE));
        int unresolved = 0;
        for (Long chargeId : chargeIds) {
            try {
                if (chargeConfirmUseCase.recover(chargeId).status() == PointChargeStatus.PROCESSING) {
                    unresolved++;
                }
            } catch (RuntimeException exception) {
                unresolved++;
                log.warn("충전 결과 확인 실패, 다음 실행에서 다시 확인한다 chargeId={}", chargeId, exception);
            }
        }
        if (!chargeIds.isEmpty()) {
            log.info("충전 결과 확인 대상 {}건, 미확정 {}건", chargeIds.size(), unresolved);
        }
        return chargeIds.size();
    }
}

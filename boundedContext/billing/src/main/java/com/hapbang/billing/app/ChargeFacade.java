package com.hapbang.billing.app;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.PointChargeStatus;
import com.hapbang.billing.out.PaymentKeyCipher;
import com.hapbang.billing.out.TossPaymentsClient;

import lombok.RequiredArgsConstructor;

// HP 충전 진입점 (#224). HP 충전은 유저·인플루언서·인증된 인플루언서만 한다(루트 AGENTS.md 6장, 관리자 403).
// 트랜잭션은 열지 않는다(PG 호출을 DB 트랜잭션 밖에 두기 위해 UseCase가 단계별로 연다).
// TossPayments 시크릿 키나 결제키 암호화 키가 없으면 상태를 바꾸기 전에 CHARGE_UNAVAILABLE(503)로 거절한다.
@Service
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('USER', 'INFLUENCER', 'VERIFIED_INFLUENCER')")
public class ChargeFacade {

    private final ChargeOrderUseCase chargeOrderUseCase;
    private final ChargeConfirmUseCase chargeConfirmUseCase;
    private final ChargeProcessing chargeProcessing;
    private final TossPaymentsClient tossPaymentsClient;
    private final PaymentKeyCipher paymentKeyCipher;

    // 결제 전 충전 주문을 만든다(PENDING, HP 변화 없음).
    public ChargeResult createOrder(Long userId, long amount) {
        requireAvailable();
        return chargeOrderUseCase.create(userId, amount);
    }

    // 결제를 승인하고 충전 결과를 돌려준다. COMPLETED(충전됨) 또는 PROCESSING(결과 확인 중)이며,
    // PG가 승인하지 않아 실패로 끝나면 CHARGE_PAYMENT_REJECTED(400)다.
    public ChargeResult confirm(Long userId, String paymentKey, String orderId, long amount) {
        requireAvailable();
        ChargeResult result = chargeConfirmUseCase.confirm(userId, paymentKey, orderId, amount);
        if (result.status() == PointChargeStatus.FAILED) {
            throw new ChargeRejectedException(result.failureReason());
        }
        return result;
    }

    // 내 충전 주문의 현재 상태
    public ChargeResult getCharge(Long userId, String orderId) {
        return chargeProcessing.resultOf(userId, orderId);
    }

    private void requireAvailable() {
        if (!tossPaymentsClient.isConfigured() || !paymentKeyCipher.isConfigured()) {
            throw new BillingException(BillingErrorCode.CHARGE_UNAVAILABLE);
        }
    }
}

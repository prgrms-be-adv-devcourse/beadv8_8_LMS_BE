package com.hapbang.billing.app;

import java.util.regex.Pattern;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.out.PaymentKeyCipher;
import com.hapbang.billing.out.TossPaymentsClient;

import lombok.RequiredArgsConstructor;

// 충전 HP 환불 진입점 (#260). HP 환불은 유저·인플루언서·인증된 인플루언서만 한다(루트 AGENTS.md 6장, 관리자 403).
// 트랜잭션은 열지 않는다(PG 호출을 DB 트랜잭션 밖에 두기 위해 UseCase가 단계별로 연다).
// TossPayments 시크릿 키나 결제키 암호화 키가 없으면 접수 전에 REFUND_UNAVAILABLE(503)로 거절한다(조회·취소는 PG가 필요 없다).
@Service
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('USER', 'INFLUENCER', 'VERIFIED_INFLUENCER')")
public class RefundFacade {

    // 클라이언트 Idempotency-Key 형식(요청 멱등키 REFUND:{walletId}:{키} 길이 제한, ':' 금지)
    static final Pattern CLIENT_KEY = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final RefundRequestUseCase refundRequestUseCase;
    private final RefundProcessUseCase refundProcessUseCase;
    private final RefundProcessing refundProcessing;
    private final TossPaymentsClient tossPaymentsClient;
    private final PaymentKeyCipher paymentKeyCipher;

    // 환불 요청을 접수(충전 HP 보류)하고 이어서 충전 건별 PG 취소를 처리한 결과를 돌려준다.
    // 같은 Idempotency-Key 재요청은 처음 요청을 이어서 처리하거나 현재 결과를 돌려준다.
    public RefundResult request(Long userId, String idempotencyKey, long amount) {
        if (!tossPaymentsClient.isConfigured() || !paymentKeyCipher.isConfigured()) {
            throw new BillingException(BillingErrorCode.REFUND_UNAVAILABLE);
        }
        if (idempotencyKey == null || !CLIENT_KEY.matcher(idempotencyKey).matches()) {
            throw new BillingException(BillingErrorCode.REFUND_IDEMPOTENCY_KEY_INVALID);
        }
        Long refundRequestId = refundRequestUseCase.create(userId, idempotencyKey, amount);
        return refundProcessUseCase.process(refundRequestId);
    }

    // 내 환불 요청의 현재 상태
    public RefundResult getRefund(Long userId, Long refundRequestId) {
        return refundProcessing.resultOf(userId, refundRequestId);
    }

    // PG 취소 전(REQUESTED)인 내 환불 요청을 취소하고 보류 HP를 모두 반환한다. 이미 PG 취소를 시작했거나 끝났으면 REFUND_NOT_CANCELABLE.
    public RefundResult cancel(Long userId, Long refundRequestId) {
        return refundProcessing.cancel(userId, refundRequestId);
    }
}

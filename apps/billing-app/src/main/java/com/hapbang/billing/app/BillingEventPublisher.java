package com.hapbang.billing.app;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.Exchange;
import com.hapbang.billing.domain.ExchangePayout;
import com.hapbang.billing.domain.PointCharge;
import com.hapbang.billing.domain.PointRefundRequest;
import com.hapbang.billing.domain.PointRefundRequestStatus;
import com.hapbang.billing.domain.Settlement;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.event.ChargeCompletedEvent;
import com.hapbang.shared.billing.event.ExchangeCompletedEvent;
import com.hapbang.shared.billing.event.ExchangeFinalFailedEvent;
import com.hapbang.shared.billing.event.ExchangePayoutFailedEvent;
import com.hapbang.shared.billing.event.ExchangeRejectedEvent;
import com.hapbang.shared.billing.event.PayoutCompletedEvent;
import com.hapbang.shared.billing.event.RefundCompletedEvent;

import lombok.RequiredArgsConstructor;

// 지갑 HP 이동 결과 이벤트를 발행한다 (#497, docs/billing/CONTEXT.md 9장).
// 상태를 끝 상태로 바꾼 같은 트랜잭션 안에서 부른다. Modulith가 발행 기록(event_publication)에 남기고 커밋 뒤 Kafka로 보내므로,
// 상태 변경이 롤백되면 이벤트도 남지 않고, 보내기에 실패하면 재발행 배치(BillingEventRepublishUseCase)가 다시 보낸다.
// 이벤트에는 사실(대상 ID·회원·금액·업무 시각)만 담고 계좌·결제키·사유 원문은 담지 않는다.
@Component
@RequiredArgsConstructor
public class BillingEventPublisher {

    private final ApplicationEventPublisher eventPublisher;
    private final WalletRepository walletRepository;

    // 충전 완료(COMPLETED) 직후
    void chargeCompleted(PointCharge charge) {
        eventPublisher.publishEvent(new ChargeCompletedEvent(charge.getId(), userIdOf(charge.getWalletId()),
                charge.getAmountHp(), charge.getPaymentAmountKrw(), charge.getCompletedAt()));
    }

    // 지급 건(정상·위약금)을 처음 저장한 직후. 재요청(replay)에서는 부르지 않는다
    void payoutCompleted(Settlement settlement) {
        eventPublisher.publishEvent(new PayoutCompletedEvent(settlement.getId(), settlement.getReceiverUserId(),
                settlement.getPayoutType(), settlement.getSourceType(), settlement.getSourceId(),
                settlement.getPaidAmountHp(), settlement.getSettledAt()));
    }

    // 환불 요청을 끝낸 직후. 환불한 금액이 있는 결과(COMPLETED·PARTIALLY_COMPLETED)만 발행한다
    void refundClosed(PointRefundRequest request) {
        PointRefundRequestStatus status = request.getStatus();
        if (status != PointRefundRequestStatus.COMPLETED && status != PointRefundRequestStatus.PARTIALLY_COMPLETED) {
            return;
        }
        eventPublisher.publishEvent(new RefundCompletedEvent(request.getId(), userIdOf(request.getWalletId()),
                request.getRequestedAmountHp(), request.getCompletedAmountHp(), request.getCompletedAt()));
    }

    // 환전 송금 완료(송금액 0원 완료 포함) 직후
    void exchangeCompleted(Exchange exchange) {
        eventPublisher.publishEvent(new ExchangeCompletedEvent(exchange.getId(), exchange.getUserId(),
                exchange.getRequestedAmountHp(), exchange.getFeeAmountHp(), exchange.getPayoutAmountKrw(),
                exchange.getCompletedAt()));
    }

    // 관리자 거절 직후
    void exchangeRejected(Exchange exchange) {
        eventPublisher.publishEvent(new ExchangeRejectedEvent(exchange.getId(), exchange.getUserId(),
                exchange.getRequestedAmountHp(), exchange.getRejectedAt()));
    }

    // 송금 시도 확정 실패 직후(시도마다)
    void exchangePayoutFailed(Exchange exchange, ExchangePayout payout) {
        eventPublisher.publishEvent(new ExchangePayoutFailedEvent(exchange.getId(), payout.getId(),
                payout.getAttemptNo(), exchange.getUserId(), payout.getAmountKrw(), payout.getFailedAt()));
    }

    // 관리자 최종 실패 확정 직후
    void exchangeFinalFailed(Exchange exchange) {
        eventPublisher.publishEvent(new ExchangeFinalFailedEvent(exchange.getId(), exchange.getUserId(),
                exchange.getRequestedAmountHp(), exchange.getFinalFailedAt()));
    }

    // 충전·환불은 지갑 ID만 가지므로 지갑 소유 회원을 찾는다. 회원 지갑만 충전·환불하므로 없으면 불변식 위반이다
    private Long userIdOf(Long walletId) {
        return walletRepository.findById(walletId)
                .map(Wallet::getUserId)
                .orElseThrow(() -> new BillingException(BillingErrorCode.WALLET_NOT_FOUND));
    }
}

package com.hapbang.billing.in;

import com.hapbang.billing.app.PaymentHoldCommand;
import com.hapbang.billing.app.PaymentHoldPromotion;
import com.hapbang.billing.app.PaymentHoldVoidCommand;
import com.hapbang.billing.app.PaymentPayoutCommand;
import com.hapbang.billing.app.PaymentPenaltyCommand;
import com.hapbang.billing.app.PaymentReleaseCommand;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldVoidCommand;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PenaltyCommand;
import com.hapbang.shared.billing.dto.ReleaseCommand;

// 내부 HTTP API가 받은 shared Command(모듈 간 HTTP 계약)를 Billing 내부 Command로 바꾼다 (#494).
// HoldFacade·UseCase가 shared 계약에 묶이지 않게 하며, 결과 쪽 변환은 BillingResultDtos가 한다.
final class BillingCommands {

    private BillingCommands() {
    }

    static PaymentHoldCommand hold(HoldCommand command) {
        return new PaymentHoldCommand(command.userId(), command.referenceType(), command.referenceId(),
                command.amountHp(), command.promotions().stream()
                        .map(promotion -> new PaymentHoldPromotion(promotion.couponId(), promotion.memberCouponId(),
                                promotion.generation(), promotion.amountHp()))
                        .toList());
    }

    static PaymentReleaseCommand release(ReleaseCommand command) {
        return new PaymentReleaseCommand(command.userId(), command.referenceType(), command.referenceId(),
                command.releaseRequestId(), command.amountHp());
    }

    static PaymentPayoutCommand payout(PayoutCommand command) {
        return new PaymentPayoutCommand(command.payerUserId(), command.referenceType(), command.referenceId(),
                command.payoutRequestId(), command.receiverUserId(), command.sourceType(), command.sourceId(),
                command.amountHp(), command.sourceTitle());
    }

    static PaymentPenaltyCommand penalty(PenaltyCommand command) {
        return new PaymentPenaltyCommand(command.payerUserId(), command.referenceType(), command.referenceId(),
                command.cancellationId(), command.receiverUserId(), command.sourceType(), command.sourceId(),
                command.releaseAmountHp(), command.payoutAmountHp(), command.sourceTitle());
    }

    static PaymentHoldVoidCommand holdVoid(HoldVoidCommand command) {
        return new PaymentHoldVoidCommand(command.userId(), command.referenceType(), command.referenceId());
    }
}

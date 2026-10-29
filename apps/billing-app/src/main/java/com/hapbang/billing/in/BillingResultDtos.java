package com.hapbang.billing.in;

import com.hapbang.billing.app.HoldResult;
import com.hapbang.billing.app.PayoutResult;
import com.hapbang.billing.app.PenaltyResult;
import com.hapbang.billing.app.ReleaseResult;
import com.hapbang.shared.billing.dto.HoldResultDto;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PayoutResultDto;
import com.hapbang.shared.billing.dto.PenaltyCommand;
import com.hapbang.shared.billing.dto.PenaltyResultDto;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.dto.ReleaseResultDto;

// Billing 내부 결과를 shared 계약 결과로 바꾼다(BD-61: 호출한 쪽이 쓰는 값만).
// 내부 HTTP API(ApiV1BillingInternalController)와 BillingPortAdapter가 같은 변환을 쓴다 (#273).
final class BillingResultDtos {

    private BillingResultDtos() {
    }

    static HoldResultDto hold(HoldResult result) {
        return new HoldResultDto(result.referenceType(), result.referenceId(), result.totalHp(), result.replayed());
    }

    static ReleaseResultDto release(ReleaseCommand command, ReleaseResult result) {
        return new ReleaseResultDto(result.referenceType(), result.referenceId(), command.releaseRequestId(),
                result.releasedHp(), result.remainingHeldHp(), result.replayed());
    }

    // 받는 회원은 요청 값이다. 재요청에서 받는 지갑이 처음과 다르면 Billing이 IDEMPOTENCY_KEY_CONFLICT로 거절하므로 결과와 같다.
    static PayoutResultDto payout(PayoutCommand command, PayoutResult result) {
        return new PayoutResultDto(result.referenceType(), result.referenceId(), command.payoutRequestId(),
                command.receiverUserId(), result.sourceType(), result.sourceId(), result.paidHp(),
                result.remainingHeldHp(), result.replayed());
    }

    // 남은 보류액은 반환·지급을 모두 적용한 뒤인 지급 결과의 값이다.
    static PenaltyResultDto penalty(PenaltyCommand command, PenaltyResult result) {
        PayoutResult payout = result.payout();
        return new PenaltyResultDto(payout.referenceType(), payout.referenceId(), result.cancellationId(),
                command.receiverUserId(), payout.sourceType(), payout.sourceId(), result.releasedHp(),
                result.paidHp(), payout.remainingHeldHp(), result.replayed());
    }
}

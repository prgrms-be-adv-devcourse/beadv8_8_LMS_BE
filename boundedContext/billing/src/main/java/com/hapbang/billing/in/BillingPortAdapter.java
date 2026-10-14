package com.hapbang.billing.in;

import org.springframework.stereotype.Component;

import com.hapbang.billing.app.HoldFacade;
import com.hapbang.billing.app.HoldResult;
import com.hapbang.billing.app.PayoutResult;
import com.hapbang.billing.app.PenaltyResult;
import com.hapbang.billing.app.ReleaseResult;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldResultDto;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PayoutResultDto;
import com.hapbang.shared.billing.dto.PenaltyCommand;
import com.hapbang.shared.billing.dto.PenaltyResultDto;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.dto.ReleaseResultDto;
import com.hapbang.shared.billing.port.BillingPort;

import lombok.RequiredArgsConstructor;

// 강의·출연이 shared의 BillingPort로 보내는 요청을 HoldFacade에 넘기고, Billing 내부 결과를 외부 계약 결과로 바꾼다.
// 트랜잭션은 열지 않는다. HoldFacade가 부르는 UseCase 프록시의 @Transactional(REQUIRED)이 호출한 쪽 트랜잭션에 참여한다.
// Billing 예외(BillingException)는 잡거나 바꾸지 않고 그대로 전파한다.
@Component
@RequiredArgsConstructor
class BillingPortAdapter implements BillingPort {

    private final HoldFacade holdFacade;

    @Override
    public HoldResultDto hold(HoldCommand command) {
        HoldResult result = holdFacade.hold(command);
        return new HoldResultDto(result.referenceType(), result.referenceId(), result.totalHp(), result.replayed());
    }

    @Override
    public ReleaseResultDto release(ReleaseCommand command) {
        ReleaseResult result = holdFacade.release(command);
        return new ReleaseResultDto(result.referenceType(), result.referenceId(), command.releaseRequestId(),
                result.releasedHp(), result.remainingHeldHp(), result.replayed());
    }

    // 받는 회원은 요청 값이다. 재요청에서 받는 지갑이 처음과 다르면 Billing이 IDEMPOTENCY_KEY_CONFLICT로 거절하므로 결과와 같다.
    @Override
    public PayoutResultDto payout(PayoutCommand command) {
        PayoutResult result = holdFacade.payout(command);
        return new PayoutResultDto(result.referenceType(), result.referenceId(), command.payoutRequestId(),
                command.receiverUserId(), result.sourceType(), result.sourceId(), result.paidHp(),
                result.remainingHeldHp(), result.replayed());
    }

    // 남은 보류액은 반환·지급을 모두 적용한 뒤인 지급 결과의 값이다.
    @Override
    public PenaltyResultDto settlePenalty(PenaltyCommand command) {
        PenaltyResult result = holdFacade.settlePenalty(command);
        PayoutResult payout = result.payout();
        return new PenaltyResultDto(payout.referenceType(), payout.referenceId(), result.cancellationId(),
                command.receiverUserId(), payout.sourceType(), payout.sourceId(), result.releasedHp(),
                result.paidHp(), payout.remainingHeldHp(), result.replayed());
    }
}

package com.hapbang.billing.in;

import java.util.List;

import com.hapbang.billing.app.HoldResult;
import com.hapbang.billing.app.HoldState;
import com.hapbang.billing.app.HoldStatePage;
import com.hapbang.billing.app.HoldVoidResult;
import com.hapbang.billing.app.PayoutResult;
import com.hapbang.billing.app.PenaltyResult;
import com.hapbang.billing.app.ReleaseResult;
import com.hapbang.shared.billing.domain.BillingHoldStatus;
import com.hapbang.shared.billing.domain.BillingSettlementStatus;
import com.hapbang.shared.billing.dto.HoldResultDto;
import com.hapbang.shared.billing.dto.HoldStateDto;
import com.hapbang.shared.billing.dto.HoldStatePageDto;
import com.hapbang.shared.billing.dto.HoldVoidResultDto;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PayoutResultDto;
import com.hapbang.shared.billing.dto.PenaltyCommand;
import com.hapbang.shared.billing.dto.PenaltyResultDto;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.dto.ReleaseResultDto;

// Billing 내부 결과를 shared 계약 결과로 바꾼다(BD-61: 호출한 쪽이 쓰는 값만).
// 내부 HTTP API(ApiV1BillingInternalController)가 쓴다 (#273).
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

    // 서비스 간 HP 대사 조회 결과 (#577). 지갑 Enum은 같은 이름의 shared Enum으로 바꾼다(상수 일치는 테스트가 확인한다)
    static List<HoldStateDto> holdStates(List<HoldState> states) {
        return states.stream().map(BillingResultDtos::holdState).toList();
    }

    static HoldStatePageDto holdStatePage(HoldStatePage page) {
        return new HoldStatePageDto(holdStates(page.items()), page.nextUpdatedAfter(), page.nextAfterId(),
                page.hasMore());
    }

    private static HoldStateDto holdState(HoldState state) {
        HoldState.Hold hold = state.hold();
        HoldState.Voided voided = state.voided();
        return new HoldStateDto(state.referenceType(), state.referenceId(),
                hold == null ? null : new HoldStateDto.Hold(BillingHoldStatus.valueOf(hold.status().name()),
                        hold.payerUserId(), amount(hold.original()), amount(hold.remaining()), amount(hold.released()),
                        amount(hold.paid()), amount(hold.captured())),
                voided == null ? null : new HoldStateDto.Voided(voided.outcome(), voided.releasedHp()),
                state.settlements().stream().map(settlement -> new HoldStateDto.Settlement(settlement.sourceType(),
                        settlement.sourceId(), settlement.payoutType(), settlement.receiverUserId(),
                        settlement.grossAmountHp(), settlement.paidAmountHp(),
                        BillingSettlementStatus.valueOf(settlement.status().name()))).toList());
    }

    private static HoldStateDto.Amount amount(HoldState.Amount amount) {
        return new HoldStateDto.Amount(amount.chargeHp(), amount.revenueHp(), amount.totalHp());
    }

    static HoldVoidResultDto holdVoid(HoldVoidResult result) {
        return new HoldVoidResultDto(result.referenceType(), result.referenceId(), result.outcome(), result.releasedHp(),
                result.replayed());
    }

    // 남은 보류액은 반환·지급을 모두 적용한 뒤인 지급 결과의 값이다.
    static PenaltyResultDto penalty(PenaltyCommand command, PenaltyResult result) {
        PayoutResult payout = result.payout();
        return new PenaltyResultDto(payout.referenceType(), payout.referenceId(), result.cancellationId(),
                command.receiverUserId(), payout.sourceType(), payout.sourceId(), result.releasedHp(),
                result.paidHp(), payout.remainingHeldHp(), result.replayed());
    }
}

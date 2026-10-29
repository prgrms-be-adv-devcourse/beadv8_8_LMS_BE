package com.hapbang.billing.in;

import org.springframework.stereotype.Component;

import com.hapbang.billing.app.HoldFacade;
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

// 강의·출연이 shared의 BillingPort로 보내는 요청을 HoldFacade에 넘기고, Billing 내부 결과를 외부 계약 결과로 바꾼다(BillingResultDtos).
// 트랜잭션은 열지 않는다. HoldFacade가 부르는 UseCase 프록시의 @Transactional(REQUIRED)이 호출한 쪽 트랜잭션에 참여한다.
// Billing 예외(BillingException)는 잡거나 바꾸지 않고 그대로 전파한다.
// HoldFacade는 ROLE_INTERNAL만 허용하므로(D63) 인증 정보 없는 호출은 거절된다. 운영에서 이 어댑터를 부르는 곳은 없고,
// 내부 HTTP API(ApiV1BillingInternalController, #273)로 전환이 끝나면 지운다(#280).
@Component
@RequiredArgsConstructor
class BillingPortAdapter implements BillingPort {

    private final HoldFacade holdFacade;

    @Override
    public HoldResultDto hold(HoldCommand command) {
        return BillingResultDtos.hold(holdFacade.hold(command));
    }

    @Override
    public ReleaseResultDto release(ReleaseCommand command) {
        return BillingResultDtos.release(command, holdFacade.release(command));
    }

    @Override
    public PayoutResultDto payout(PayoutCommand command) {
        return BillingResultDtos.payout(command, holdFacade.payout(command));
    }

    @Override
    public PenaltyResultDto settlePenalty(PenaltyCommand command) {
        return BillingResultDtos.penalty(command, holdFacade.settlePenalty(command));
    }
}

package com.hapbang.appearance.out.billing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

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

/**
 * 실제 Billing 모듈을 shared {@link BillingPort}로 부르는 {@link BillingClient} 구현 (#182).
 * <p>
 * {@code appearance.billing.mode=billing-port}일 때만 빈으로 등록된다. 기본값은 여전히 {@code fake}({@link FakeBillingClient})이고,
 * 이 값을 바꾸는 시점은 출연 담당이 정한다.
 * <ul>
 *     <li>요청·응답을 그대로 넘긴다. Billing 업무 예외({@code BusinessException} 하위, 예: 409 {@code INSUFFICIENT_BALANCE})도 바꾸지 않고 전파한다.</li>
 *     <li>트랜잭션은 열지 않는다. Billing UseCase가 REQUIRED로 열거나 호출한 쪽 트랜잭션에 참여한다.
 *     출연은 지금 DB 트랜잭션 밖에서 부르므로(업무 상태 커밋 후 호출·HpTask 재시도) Billing 처리는 별도 트랜잭션으로 커밋된다.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "appearance.billing", name = "mode", havingValue = "billing-port")
@RequiredArgsConstructor
public class BillingPortClient implements BillingClient {

    private final BillingPort billingPort;

    @Override
    public HoldResultDto hold(HoldCommand command) {
        return billingPort.hold(command);
    }

    @Override
    public ReleaseResultDto release(ReleaseCommand command) {
        return billingPort.release(command);
    }

    @Override
    public PayoutResultDto payout(PayoutCommand command) {
        return billingPort.payout(command);
    }

    @Override
    public PenaltyResultDto settlePenalty(PenaltyCommand command) {
        return billingPort.settlePenalty(command);
    }
}

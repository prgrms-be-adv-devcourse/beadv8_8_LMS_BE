package com.hapbang.appearance.out.billing;

import org.springframework.stereotype.Component;

import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldResultDto;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PayoutResultDto;
import com.hapbang.shared.billing.dto.PenaltyCommand;
import com.hapbang.shared.billing.dto.PenaltyResultDto;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.dto.ReleaseResultDto;
import com.hapbang.shared.billing.out.BillingApiClient;
import com.hapbang.shared.billing.out.BillingCallResult;

import lombok.RequiredArgsConstructor;

/**
 * 지갑 내부 HTTP API를 공용 {@link BillingApiClient}(#272)로 부르는 {@link BillingClient} 구현 (#274).
 * <ul>
 *     <li>결과를 바꾸지 않고 넘긴다. 결과 해석(보류 실패 응답, HP 작업 재시도·격리)은 출연 UseCase가 한다.</li>
 *     <li>주소·내부 API 키·응답 대기 시간·Circuit Breaker·짧은 재시도(처리하지 않은 것이 확실한 실패만)는 공용 Client 설정
 *     ({@code hapbang.clients.billing.*})이다. 지갑 주소가 없으면 공용 Client 빈이 없어 출연 앱이 뜨지 않는다.</li>
 *     <li>트랜잭션은 열지 않는다. 지갑은 별도 앱이라 출연 트랜잭션과 묶이지 않는다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class BillingHttpClient implements BillingClient {

    private final BillingApiClient billingApiClient;

    @Override
    public BillingCallResult<HoldResultDto> hold(HoldCommand command) {
        return billingApiClient.hold(command);
    }

    @Override
    public BillingCallResult<ReleaseResultDto> release(ReleaseCommand command) {
        return billingApiClient.release(command);
    }

    @Override
    public BillingCallResult<PayoutResultDto> payout(PayoutCommand command) {
        return billingApiClient.payout(command);
    }

    @Override
    public BillingCallResult<PenaltyResultDto> settlePenalty(PenaltyCommand command) {
        return billingApiClient.settlePenalty(command);
    }

    @Override
    public boolean isCircuitOpen() {
        return billingApiClient.isCircuitOpen();
    }
}

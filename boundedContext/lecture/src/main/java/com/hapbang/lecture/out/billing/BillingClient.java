package com.hapbang.lecture.out.billing;

import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldResultDto;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PayoutResultDto;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.dto.ReleaseResultDto;

/**
 * 강의가 소유한 billing 호출 Port (설계 6.2, D56·D62). 구현은 조립 모듈 {@code apps}의 In-process Adapter다.
 * <ul>
 *     <li>billing이 처리하면 {@link BillingCallResult.Succeeded}, 처리하지 못했다고 응답하면 {@link BillingCallResult.Failed}를 돌려준다.</li>
 *     <li>결과를 받지 못한 실패는 결과가 아니라 {@link BillingCallException}이다.</li>
 * </ul>
 * 요청 값 오류(null ID 등)는 요청 DTO가 만들 때 {@code NullPointerException}·{@code IllegalArgumentException}으로 막는다.
 */
public interface BillingClient {

    BillingCallResult<HoldResultDto> hold(HoldCommand command);

    BillingCallResult<ReleaseResultDto> release(ReleaseCommand command);

    BillingCallResult<PayoutResultDto> payout(PayoutCommand command);
}

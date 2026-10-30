package com.hapbang.appearance.out.billing;

import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldResultDto;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PayoutResultDto;
import com.hapbang.shared.billing.dto.PenaltyCommand;
import com.hapbang.shared.billing.dto.PenaltyResultDto;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.dto.ReleaseResultDto;
import com.hapbang.shared.billing.out.BillingCallException;
import com.hapbang.shared.billing.out.BillingCallResult;

/**
 * 지갑(billing-app)과의 HP 연동 창구. 출연 업무 코드는 이 인터페이스로만 지갑을 부른다.
 * <p>
 * 요청·응답은 지갑 내부 API 계약({@code docs/api/internal-billing.md}, {@code shared.billing.dto})과 같다.
 * <ul>
 *     <li>지갑이 응답한 결과는 {@link BillingCallResult}다: 처리함({@code Succeeded}, 같은 요청을 다시 보냈으면 처음 결과),
 *     처리하지 않고 실패로 응답함({@code Failed}, 코드·사용자용 설명·HTTP 상태). 호출한 쪽이 결과 종류마다 분기한다.</li>
 *     <li>결과를 받지 못하면 {@link BillingCallException}이다: 지갑이 처리했는지 알 수 없거나({@code UNKNOWN_RESULT}),
 *     처리하지 않은 것이 확실하다({@code RETRYABLE}). 어느 쪽이든 같은 요청(같은 멱등 키)으로 다시 보내 결과를 확인한다.</li>
 *     <li>보류 멱등 키는 지갑이 참조 대상으로 만든다({@code HOLD:{참조 유형}:{참조 ID}}, 참조 대상당 보류 1건).
 *     반환·지급·위약금은 출연이 업무 요청마다 안정적으로 만든 요청 ID로 구분한다.</li>
 *     <li>출연은 DB 트랜잭션 밖에서 부른다. 업무 상태를 먼저 커밋하고 지갑 결과는 따로 기록한다.</li>
 * </ul>
 * 구현은 공용 HTTP Client를 부르는 {@link BillingHttpClient}(#274) 하나다. 출연 테스트는 Mockito 대역(기본 성공)이나
 * Mock Billing 서버를 쓴다.
 */
public interface BillingClient {

    /** 참조 대상(요청서·모집글) 하나에 호스트의 HP를 보류한다. 잔액이 부족하면 {@code Failed(INSUFFICIENT_BALANCE)} */
    BillingCallResult<HoldResultDto> hold(HoldCommand command);

    /** 보류 HP 일부 또는 전부를 호스트에게 반환한다 (차감했던 HP 종류 그대로) */
    BillingCallResult<ReleaseResultDto> release(ReleaseCommand command);

    /** 보류 HP 일부 또는 전부를 게스트의 수익 HP로 지급한다. 지급 원천(출연 계약)마다 한 번만 */
    BillingCallResult<PayoutResultDto> payout(PayoutCommand command);

    /** 호스트 일방 취소: 출연이 계산한 반환액은 호스트에게, 위약금은 게스트에게 한 번에 처리한다 */
    BillingCallResult<PenaltyResultDto> settlePenalty(PenaltyCommand command);
}

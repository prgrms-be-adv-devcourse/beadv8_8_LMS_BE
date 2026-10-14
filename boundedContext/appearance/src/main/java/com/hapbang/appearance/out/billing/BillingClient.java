package com.hapbang.appearance.out.billing;

import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldResultDto;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PayoutResultDto;
import com.hapbang.shared.billing.dto.PenaltyCommand;
import com.hapbang.shared.billing.dto.PenaltyResultDto;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.dto.ReleaseResultDto;
import com.hapbang.shared.billing.port.BillingPort;

/**
 * 지갑+정산(billing) 모듈과의 HP 연동 창구. 출연 모듈은 이 인터페이스로만 Billing을 호출한다.
 * <p>
 * 메서드·요청·응답은 Billing이 shared에 둔 연동 계약({@link BillingPort})과 같다.
 * <ul>
 *     <li>보류 멱등 키는 Billing이 참조 대상으로 만든다({@code HOLD:{참조 유형}:{참조 ID}}, 참조 대상당 보류 1건).
 *     반환·지급·위약금은 출연이 업무 요청마다 안정적으로 만든 요청 ID로 구분한다. 같은 요청을 다시 보내면 처음 결과를 돌려준다.</li>
 *     <li>Billing이 거절하면(잔액 부족 등) Billing 업무 예외({@code BusinessException} 하위)가 난다.
 *     출연은 코드·메시지를 바꾸지 않고 그대로 응답한다.</li>
 *     <li>그 밖의 예외는 처리됐는지 알 수 없는 실패다. 같은 요청으로 다시 보내 결과를 확인한다.</li>
 *     <li>출연은 DB 트랜잭션 밖에서 부른다. 업무 상태를 먼저 커밋하고 Billing 결과는 따로 기록한다.</li>
 * </ul>
 * {@link BillingPort}를 상속하지 않는다. 상속하면 출연의 Fake가 앱 전체의 {@link BillingPort} 빈이 되어 다른 모듈에 주입될 수 있다.
 * <p>
 * 구현체는 {@code appearance.billing.mode} 값으로 고른다. 지금은 {@link FakeBillingClient}만 있다.
 */
// TODO(billing): 외부 연동 교체 단계에서 BillingPort를 부르는 구현체를 추가한다.
public interface BillingClient {

    /** 참조 대상(요청서·모집글) 하나에 호스트의 HP를 보류한다. 잔액이 부족하면 409 {@code INSUFFICIENT_BALANCE} */
    HoldResultDto hold(HoldCommand command);

    /** 보류 HP 일부 또는 전부를 호스트에게 반환한다 (차감했던 HP 종류 그대로) */
    ReleaseResultDto release(ReleaseCommand command);

    /** 보류 HP 일부 또는 전부를 게스트의 수익 HP로 지급한다. 지급 원천(출연 계약)마다 한 번만 */
    PayoutResultDto payout(PayoutCommand command);

    /** 호스트 일방 취소: 출연이 계산한 반환액은 호스트에게, 위약금은 게스트에게 한 번에 처리한다 */
    PenaltyResultDto settlePenalty(PenaltyCommand command);
}

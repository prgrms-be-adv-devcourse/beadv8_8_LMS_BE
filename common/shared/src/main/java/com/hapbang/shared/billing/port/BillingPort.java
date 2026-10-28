package com.hapbang.shared.billing.port;

import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldResultDto;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PayoutResultDto;
import com.hapbang.shared.billing.dto.PenaltyCommand;
import com.hapbang.shared.billing.dto.PenaltyResultDto;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.dto.ReleaseResultDto;

/**
 * 강의·출연이 HP 보류·반환·지급·위약금 정산을 요청하는 Billing 연동 계약. 구현은 Billing 모듈이 Spring 빈으로 제공한다.
 * <p>
 * 호출 규칙
 * <ul>
 *     <li>같은 애플리케이션 안의 동기 Java 호출이다. 각 메서드는 호출한 쪽 트랜잭션에 참여하고(전파 REQUIRED), 없으면 새로 연다.</li>
 *     <li>금융 실패는 런타임 예외(Billing의 {@code BusinessException} 하위 예외, 예: 잔액 부족 409 {@code INSUFFICIENT_BALANCE})로 알린다.
 *         예외가 나면 Billing 변경과 함께 호출한 쪽의 같은 트랜잭션 변경도 롤백된다.
 *         호출한 쪽이 예외를 잡아 정상 처리로 바꾸면 이미 롤백 전용이 된 트랜잭션을 이어 쓰게 되므로 잡지 말고 그대로 전파한다.</li>
 *     <li>요청 DTO의 구조 오류(필수 값 누락, 보류할 수 없는 참조 유형, 요청 ID 형식)는 호출한 쪽 코드 오류라
 *         {@code NullPointerException}·{@code IllegalArgumentException}으로 DTO를 만들 때 바로 난다.</li>
 *     <li>멱등키는 Billing이 만든다. 보류는 참조 대상당 1건({@code HOLD:{referenceType}:{referenceId}})이고, 반환·지급·위약금은
 *         호출한 쪽이 업무 요청마다 안정적으로 만든 요청 ID(반환 요청 ID·지급 요청 ID·취소 ID)로 재요청을 구분한다.</li>
 * </ul>
 */
public interface BillingPort {

    /**
     * 참조 대상 하나에 결제 회원의 HP를 보류한다. 수익 HP를 먼저, 부족분은 충전 HP를 쓴다.
     * 같은 참조 대상·같은 회원·같은 금액으로 다시 보내면 기존 보류를 돌려준다({@code replayed=true}).
     * 같은 참조 대상에 다른 회원·다른 금액이면 {@code IDEMPOTENCY_KEY_CONFLICT}다.
     */
    HoldResultDto hold(HoldCommand command);

    /**
     * 참조 대상 보류의 남은 HP 일부 또는 전부를 결제 회원에게 차감했던 HP 종류 그대로 반환한다.
     * 같은 반환 요청 ID로 다시 보내면 처음 결과를 돌려준다.
     */
    ReleaseResultDto release(ReleaseCommand command);

    /**
     * 참조 대상 보류의 남은 HP 일부 또는 전부를 받는 회원의 수익 HP로 지급한다(정상 지급).
     * 한 지급 원천(출연 계약·주문 항목)에는 지급 유형과 관계없이 한 번만 지급한다. 같은 지급 요청 ID로 다시 보내면 처음 결과를 돌려준다.
     */
    PayoutResultDto payout(PayoutCommand command);

    /**
     * 호스트 일방 취소를 정산한다. 출연이 계산한 호스트 반환액을 반환하고 게스트 지급액을 위약금으로 지급하며, 둘을 한 트랜잭션에서 처리한다.
     * Billing은 위약금 비율을 계산하지 않는다. 같은 취소 ID·같은 금액으로 다시 보내면 처음 결과를 돌려준다.
     */
    PenaltyResultDto settlePenalty(PenaltyCommand command);
}

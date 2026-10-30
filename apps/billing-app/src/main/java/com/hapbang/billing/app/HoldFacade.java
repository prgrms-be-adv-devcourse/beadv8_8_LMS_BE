package com.hapbang.billing.app;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldVoidCommand;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PenaltyCommand;
import com.hapbang.shared.billing.dto.ReleaseCommand;

import lombok.RequiredArgsConstructor;

// HP 보류·반환·지급·위약금 정산의 Billing 모듈 진입점이며, 트랜잭션은 UseCase가 연다.
// 강의·출연 서비스가 결제자 본인 확인을 마친 뒤 호출하는 내부 금융 기능이라 회원 역할이 아니라 내부 호출(ROLE_INTERNAL)만 허용한다
// (D63, #273). 내부 API 보안 체인이 X-Internal-Api-Key를 확인하고 ROLE_INTERNAL을 준다.
// 다른 모듈은 이 클래스가 아니라 내부 HTTP API(in.ApiV1BillingInternalController, #273)나 shared의 BillingPort(구현: in.BillingPortAdapter)로
// 호출한다. 이 클래스는 Billing 내부 결과(충전 건별 배분 등)를 돌려준다. 락 대기 초과는 HP_LOCK_TIMEOUT(503)으로 바꾼다(LockTimeouts).
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('INTERNAL')")
public class HoldFacade {

    private final HoldCreateUseCase holdCreateUseCase;
    private final HoldReleaseUseCase holdReleaseUseCase;
    private final HoldPayoutUseCase holdPayoutUseCase;
    private final PenaltySettlementUseCase penaltySettlementUseCase;
    private final HoldVoidUseCase holdVoidUseCase;

    // 참조 대상 하나에 결제 회원의 HP를 보류하며, 같은 요청을 다시 보내면 기존 보류 결과를 돌려준다.
    public HoldResult hold(HoldCommand command) {
        return LockTimeouts.translate(() -> holdCreateUseCase.hold(command));
    }

    // 참조 대상 보류의 남은 HP 일부 또는 전부를 결제 회원에게 반환하며, 같은 반환 요청 ID로 다시 보내면 처음 반환 결과를 돌려준다.
    // 반환 여부와 금액은 강의·출연이 업무 상태로 정하고, Billing은 금액 한도·보류 소유·멱등성·금융 정합성만 검증한다.
    public ReleaseResult release(ReleaseCommand command) {
        return LockTimeouts.translate(() -> holdReleaseUseCase.release(command));
    }

    // 참조 대상 보류의 남은 HP 일부 또는 전부를 받는 회원의 수익 HP로 지급하며(정상 지급), 같은 지급 요청 ID로 다시 보내면 처음 결과를 돌려준다.
    // 지급 가능 여부와 금액은 강의·출연이 업무 상태로 정하고, Billing은 지갑·보류·지급 원천·금액·멱등성·금융 정합성만 검증한다.
    public PayoutResult payout(PayoutCommand command) {
        return LockTimeouts.translate(() -> holdPayoutUseCase.payout(command));
    }

    // 호스트 일방 취소 위약금을 정산한다: 출연이 계산한 호스트 반환액을 반환하고 게스트 지급액을 위약금으로 지급한다(한 트랜잭션).
    // 위약금 비율은 출연이 정하며 Billing은 계산하지 않는다. 같은 취소 ID·같은 금액으로 다시 보내면 처음 정산 결과를 돌려준다.
    public PenaltyResult settlePenalty(PenaltyCommand command) {
        return LockTimeouts.translate(() -> penaltySettlementUseCase.settle(command));
    }

    // 참조 대상의 보류를 더는 쓰지 않는다고 받아 남은 보류를 반환하고, 보류가 없으면 앞으로 그 참조 대상에 보류가 생기지 않게 한다 (#479).
    // 참조 대상당 한 번이며 같은 요청을 다시 보내면 처음 결과를 돌려준다.
    public HoldVoidResult voidHold(HoldVoidCommand command) {
        return LockTimeouts.translate(() -> holdVoidUseCase.voidHold(command));
    }
}

package com.hapbang.billing.app;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

// HP 보류·반환·지급·위약금 정산의 Billing 모듈 진입점이며, 트랜잭션은 UseCase가 연다.
// 강의·출연 서비스가 결제자 본인 확인을 마친 뒤 호출하는 내부 금융 기능이라 역할 검사(@PreAuthorize)를 두지 않는다.
@Service
@RequiredArgsConstructor
public class HoldFacade {

    private final HoldCreateUseCase holdCreateUseCase;
    private final HoldReleaseUseCase holdReleaseUseCase;
    private final HoldPayoutUseCase holdPayoutUseCase;
    private final PenaltySettlementUseCase penaltySettlementUseCase;

    // 참조 대상 하나에 결제 회원의 HP를 보류하며, 같은 요청을 다시 보내면 기존 보류 결과를 돌려준다.
    public HoldResult hold(HoldCommand command) {
        return holdCreateUseCase.hold(command);
    }

    // 참조 대상 보류의 남은 HP 일부 또는 전부를 결제 회원에게 반환하며, 같은 반환 요청 ID로 다시 보내면 처음 반환 결과를 돌려준다.
    // 반환 여부와 금액은 강의·출연이 업무 상태로 정하고, Billing은 금액 한도·보류 소유·멱등성·금융 정합성만 검증한다.
    public ReleaseResult release(ReleaseCommand command) {
        return holdReleaseUseCase.release(command);
    }

    // 참조 대상 보류의 남은 HP 일부 또는 전부를 받는 회원의 수익 HP로 지급하며(정상 지급), 같은 지급 요청 ID로 다시 보내면 처음 결과를 돌려준다.
    // 지급 가능 여부와 금액은 강의·출연이 업무 상태로 정하고, Billing은 지갑·보류·지급 원천·금액·멱등성·금융 정합성만 검증한다.
    public PayoutResult payout(PayoutCommand command) {
        return holdPayoutUseCase.payout(command);
    }

    // 호스트 일방 취소 위약금을 정산한다: 정산 기준액의 30%(소수점 버림)를 게스트에게 지급하고 나머지를 호스트에게 반환한다(한 트랜잭션).
    // 같은 취소 ID로 다시 보내면 처음 정산 결과를 돌려준다.
    public PenaltyResult settlePenalty(PenaltyCommand command) {
        return penaltySettlementUseCase.settle(command);
    }
}

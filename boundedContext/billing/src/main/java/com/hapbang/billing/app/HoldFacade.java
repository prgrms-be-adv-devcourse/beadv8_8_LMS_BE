package com.hapbang.billing.app;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

// HP 보류의 Billing 모듈 진입점이며, 트랜잭션은 UseCase가 연다.
// 강의·출연 서비스가 결제자 본인 확인을 마친 뒤 호출하는 내부 금융 기능이라 역할 검사(@PreAuthorize)를 두지 않는다.
@Service
@RequiredArgsConstructor
public class HoldFacade {

    private final HoldCreateUseCase holdCreateUseCase;

    // 참조 대상 하나에 결제 회원의 HP를 보류하며, 같은 요청을 다시 보내면 기존 보류 결과를 돌려준다.
    public HoldResult hold(HoldCommand command) {
        return holdCreateUseCase.hold(command);
    }
}

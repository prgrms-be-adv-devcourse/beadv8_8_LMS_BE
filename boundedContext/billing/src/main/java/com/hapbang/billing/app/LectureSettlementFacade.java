package com.hapbang.billing.app;

import java.time.Instant;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

// 강의 판매 수익 지급 예정 건 관리의 Billing 모듈 진입점이며, 트랜잭션은 UseCase가 연다(#142).
// 강의 구매·취소와 고객센터 이의 제기 처리가 업무 판단을 마친 뒤 부르는 내부 금융 기능이라 역할 검사(@PreAuthorize)를 두지 않는다.
// 아직 다른 모듈이 부르지 않는다. 강의 연동(#146) 때 shared 포트를 넓힐지, 별도 정산 포트를 둘지 정한다.
@Service
@RequiredArgsConstructor
public class LectureSettlementFacade {

    private final LectureSettlementUseCase lectureSettlementUseCase;

    // 주문 항목 보류를 확인하고 판매자에게 줄 지급 예정 건(SCHEDULED)을 만든다. 같은 내용을 다시 보내면 기존 건을 돌려준다.
    public LectureSettlementResult schedule(LectureSettlementCommand command) {
        return lectureSettlementUseCase.schedule(command);
    }

    // 유효한 이의 제기가 접수되면 지급을 멈춘다.
    public LectureSettlementResult suspend(Long orderItemId) {
        return lectureSettlementUseCase.suspend(orderItemId);
    }

    // 이의 제기가 기각되거나 지급이 허용되면 처음 예정 시각으로 지급을 다시 예정한다.
    public LectureSettlementResult resume(Long orderItemId) {
        return lectureSettlementUseCase.resume(orderItemId);
    }

    // 구매 취소·환불 결정이 확정되면 지급 예정 건을 취소한다. 보류 반환(BillingPort.release)은 강의가 같은 트랜잭션에서
    // 이 호출보다 먼저 요청한다(반환 → 취소 순서, 락 순서 지갑 → 보류 → 지급 건).
    public LectureSettlementResult cancel(Long orderItemId) {
        return lectureSettlementUseCase.cancel(orderItemId);
    }

    // 주어진 시각에 그 주문 항목의 지급 건이 지급 상태 조건(SCHEDULED + 예정 시각 경과)을 만족하는지 확인한다. 지급 승인이 아니다.
    public boolean isPayable(Long orderItemId, Instant now) {
        return lectureSettlementUseCase.isPayable(orderItemId, now);
    }
}

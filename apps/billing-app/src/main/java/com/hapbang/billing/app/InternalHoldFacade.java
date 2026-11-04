package com.hapbang.billing.app;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

// Billing 내부 종류별 보류·확정 차감·반환 진입점 (#241). 후속 충전 HP 환불·수익 HP 환전 UseCase만 쓴다.
// 다른 모듈에 공개하지 않는다(강의·출연은 내부 HTTP API의 보류·반환·지급·위약금 정산을 쓴다).
// 트랜잭션은 UseCase가 연다(호출한 쪽 트랜잭션이 있으면 참여). 결과는 Billing 내부 결과(충전 건별 배분·소진·복원 포함)다.
//
// 사용 예 (환불, #260 RefundProcessing): hold(POINT_REFUND_REQUEST, 환불 요청 ID, 금액) → PG 결제 건(충전 건)별 취소
//   성공: capture(ofCharge(..., "REFUND-{개별 환불 ID}", 충전 건 ID, PG가 취소한 금액))
//   실패 확정·PG 미요청 종료: release(ofCharge(..., "REFUND-{개별 환불 ID}", 충전 건 ID, 그 결제 건 금액))
//   결과 불명확: 아무것도 하지 않음(보류 유지, 재조회 후 위 둘 중 하나)
// 사용 예 (환전): hold(EXCHANGE, 환전 ID, 금액) → 송금 성공 capture(full) / 최종 거절·취소 release(full) / 불명확 보류 유지
@Service
@RequiredArgsConstructor
public class InternalHoldFacade {

    private final HoldCreateUseCase holdCreateUseCase;
    private final HoldCaptureUseCase holdCaptureUseCase;
    private final HoldReleaseUseCase holdReleaseUseCase;

    // 참조 유형으로 정해진 종류(CHARGE_ONLY·REVENUE_ONLY)로 보류한다. 같은 참조 대상 재요청은 처음 보류 결과다.
    public HoldResult hold(InternalHoldCommand command) {
        return holdCreateUseCase.hold(command);
    }

    // 보류 HP를 확정 차감한다(전체·부분, 충전 건 지정 가능). 같은 확정 차감 요청 ID 재요청은 처음 결과다.
    public CaptureResult capture(CaptureCommand command) {
        return holdCaptureUseCase.capture(command);
    }

    // 보류 HP를 보류한 회원에게 반환한다(전체·부분, 충전 건 지정 가능). 같은 반환 요청 ID 재요청은 처음 결과다.
    public ReleaseResult release(InternalReleaseCommand command) {
        return holdReleaseUseCase.release(command);
    }
}

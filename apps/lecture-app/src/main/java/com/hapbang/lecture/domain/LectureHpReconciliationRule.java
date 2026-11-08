package com.hapbang.lecture.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 서비스 간 HP 대사 규칙 (#580, D74). 주문 항목 상태로 정한 기대값과 지갑 기록이 어떻게 어긋났는지다.
 * 출연 대사(#581)와 뜻이 같은 규칙은 같은 이름을 써 지표 {@code rule} label을 맞춘다.
 */
@Schema(description = """
        HP 대사 규칙
        - `HOLD_MISSING`: 보류가 있어야 하는데 지갑에 없음
        - `HOLD_MISMATCH`: 최초 보류액이 항목 가격과 다르거나 보류한 회원이 구매자가 아님
        - `HOLD_BALANCE_MISMATCH`: 남은 보류·반환·지급·확정 차감이 항목 상태의 기대값과 다름 (예: 취소됐는데 HELD)
        - `HOLD_NOT_CLOSED`: 결제 실패인데 남은 보류·지급·확정 차감이 있음
        - `VOIDED_WHILE_ACTIVE`: 결제 실패가 아닌데 보류 무효화 기록이 있음
        - `SETTLEMENT_MISSING`: 지급됐는데 지급 건이 없음
        - `SETTLEMENT_MISMATCH`: 지급 건의 원천·받는 회원(판매자)·금액·상태가 다르거나 여러 건임
        - `SETTLEMENT_UNEXPECTED`: 지급 전·취소·반환·결제 실패인데 지급 건이 있음
        - `WALLET_ONLY_HOLD`: 지갑 보류의 주문 항목이 강의에 없음
        - `HP_TASK_DEAD`: 자동 처리가 멈춘 HP 작업이 있어 비교하지 않았다는 표시 (어긋난 건 수에서 뺀다)""")
public enum LectureHpReconciliationRule {
    HOLD_MISSING,
    HOLD_MISMATCH,
    HOLD_BALANCE_MISMATCH,
    HOLD_NOT_CLOSED,
    VOIDED_WHILE_ACTIVE,
    SETTLEMENT_MISSING,
    SETTLEMENT_MISMATCH,
    SETTLEMENT_UNEXPECTED,
    WALLET_ONLY_HOLD,
    HP_TASK_DEAD
}

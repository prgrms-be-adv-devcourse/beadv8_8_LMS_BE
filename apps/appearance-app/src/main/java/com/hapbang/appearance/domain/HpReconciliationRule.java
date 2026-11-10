package com.hapbang.appearance.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 서비스 간 HP 대사 규칙 (#581). 출연 기록과 지갑 기록이 어긋난 이유다.
 * <p>
 * 비교는 두 단계다. ① 출연 상태로 계산한 기대값과 끝난(DONE) HP 작업의 합이 같은지(출연 쪽 기록 누락),
 * ② 끝난 HP 작업의 합·지급 건이 지갑 조회 결과와 같은지(지갑 쪽 반영 누락·중복). {@link #HP_TASK_DEAD}는 어긋남이 아니라 표시다.
 */
@Getter
@RequiredArgsConstructor
public enum HpReconciliationRule {
    HP_TASK_MISMATCH("① 상태로 계산한 남은 보류·지급액과 끝난 HP 작업의 합이 다르다 (작업이 없거나 금액이 다름)"),
    HOLD_MISSING("② 보류가 있어야 하는데 지갑에 없다"),
    HOLD_MISMATCH("② 최초 보류액이나 보류한 회원이 다르다"),
    HOLD_BALANCE_MISMATCH("② 반환·지급·확정 차감·남은 보류가 끝난 HP 작업의 합과 다르다"),
    HOLD_NOT_CLOSED("② 보류 실패로 끝난 요청서·모집글인데 지갑 보류가 남았거나 지급됐다"),
    VOIDED_WHILE_ACTIVE("② 진행 중인 요청서·모집글인데 지갑에 보류 무효화 기록이 있다 (#536)"),
    SETTLEMENT_MISSING("② 끝난 지급·위약금 작업의 지급 건이 지갑에 없다"),
    SETTLEMENT_MISMATCH("② 지급 건의 받는 회원·금액·상태가 작업과 다르다"),
    SETTLEMENT_UNEXPECTED("② 출연 작업에 없는 지급 건이 지갑에 있다"),
    WALLET_ONLY_HOLD("② 지갑에만 있는 보류 (지갑 목록의 참조 ID가 출연에 없다)"),
    HP_TASK_DEAD("격리된(DEAD) HP 작업이 있어 비교하지 않고 표시만 한다 (관리자 HP 작업 다시 시도 대상)");

    private final String description;
}

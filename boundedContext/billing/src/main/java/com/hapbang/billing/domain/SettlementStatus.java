package com.hapbang.billing.domain;

// 수익 HP 지급의 예정·중단·처리 중·완료·실패·취소 상태를 구분한다.
// 지금 지급 건을 만드는 경로는 즉시 지급(BillingPort.payout·settlePenalty)뿐이며 처리하면서 바로 COMPLETED 건을 만든다.
// 강의 지급도 강의가 지급 시점을 판단해 BillingPort.payout을 직접 부른다(#188). SCHEDULED·SUSPENDED·CANCELLED를 만드는 코드는 없다.
public enum SettlementStatus {
    /** 지급 예정. 예정 시각(scheduled_at)이 되면 처리한다 */
    SCHEDULED,
    /** 지급 중단. 이의 제기 등으로 지급하지 않고 멈춰 둔다 */
    SUSPENDED,
    /** 지급 처리 중 */
    PROCESSING,
    /** 받는 쪽 수익 HP로 지급을 마쳤다 */
    COMPLETED,
    /** 지급 실패(예: 받는 쪽 지갑 없음). 재처리 대상 */
    FAILED,
    /**
     * 지급하지 않기로 한 종료 상태. #142의 강의 지급 예정 건 취소용으로 더했고(V202610141530), #188에서 그 경로를 없애 지금은 만드는 코드가 없다.
     * DB CHECK 허용값과 이미 저장됐을 수 있는 행을 위해 상수를 남긴다(상수 이름 = DB 저장값).
     */
    CANCELLED
}

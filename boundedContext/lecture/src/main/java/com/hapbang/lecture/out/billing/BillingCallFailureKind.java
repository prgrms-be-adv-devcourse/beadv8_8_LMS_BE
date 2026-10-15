package com.hapbang.lecture.out.billing;

/** billing 결과를 받지 못한 실패가 처리됐을 수 있는지 (D56) */
public enum BillingCallFailureKind {
    /** 호출 전에 실패했거나 billing 변경이 롤백된 것이 확실하다. 같은 요청으로 다시 보내도 된다 */
    RETRYABLE,
    /** billing이 처리했는지 알 수 없다(응답 타임아웃·유실). 같은 요청 ID로 다시 보내 결과를 확인한다 */
    UNKNOWN_RESULT
}

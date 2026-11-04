package com.hapbang.billing.domain;

// 여러 충전 건(PG 결제 건)에 걸친 충전 HP 환불 요청의 진행 상태다 (#260). 충전 건별 PG 취소 상태는 PointRefundStatus다.
public enum PointRefundRequestStatus {
    /** 충전 HP를 보류했고 아직 PG에 취소를 요청하지 않았다. 회원이 취소할 수 있다 */
    REQUESTED,
    /** 충전 건별 PG 취소를 진행 중이다(결과 불명확한 건의 재확인 포함). 회원이 취소할 수 없다 */
    PROCESSING,
    /** 일부 충전 건만 환불하고 끝났다(앞선 건 성공 후 실패 확정). 환불하지 못한 금액은 반환했다 */
    PARTIALLY_COMPLETED,
    /** 요청한 금액을 모두 환불했다 */
    COMPLETED,
    /** 환불한 금액 없이 실패가 확정되었다. 보류 HP를 모두 반환했다 */
    FAILED,
    /** PG 결과와 기록이 맞지 않아 운영자 확인이 필요하다. 해당 충전 건 금액은 보류로 남는다 */
    MANUAL_REVIEW_REQUIRED,
    /** PG 취소를 요청하기 전에 회원이 취소했다. 보류 HP를 모두 반환했다 */
    CANCELED
}

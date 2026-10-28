package com.hapbang.billing.domain;

// 환불 요청에 속한 충전 건 하나(PG 결제 건 하나)의 PG 부분 취소 상태다 (#260).
public enum PointRefundStatus {
    /** 아직 PG에 취소를 요청하지 않았다(보류 유지) */
    PENDING,
    /** PG에 취소를 요청했고 결과를 기다린다(보류 유지). 요청 전에 이 상태를 먼저 커밋한다 */
    PROCESSING,
    /** PG 취소 성공. PG가 취소한 금액만큼 보류 HP를 확정 차감했다 */
    SUCCEEDED,
    /** PG 취소 실패가 확정되었다. 보류 HP를 반환했다 */
    FAILED,
    /** PG 결과가 불명확하다(시간 초과·5xx 등). 보류를 유지하고 같은 멱등키로 결과 확인한다 */
    UNKNOWN,
    /** PG 결과와 기록이 맞지 않아 운영자 확인이 필요하다(보류 유지) */
    MANUAL_REVIEW_REQUIRED,
    /** PG에 취소를 요청하지 않고 끝났다(회원 취소·앞선 결제 건 실패로 중단). 보류 HP를 반환했다 */
    CANCELED
}

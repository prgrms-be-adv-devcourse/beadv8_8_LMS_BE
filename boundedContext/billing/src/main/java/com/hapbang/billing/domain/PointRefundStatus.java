package com.hapbang.billing.domain;

// 개별 충전 건에 대한 PG 환불의 진행 상태를 구분하며 결과가 불명확하면 UNKNOWN으로 표현한다.
// 환불 기능은 아직 구현 전이며, 상태 의미는 billing AGENTS.md 10장(충전·환불)을 따른다.
public enum PointRefundStatus {
    /** 충전 건 하나의 PG 취소를 아직 요청하지 않았다 */
    PENDING,
    /** PG 취소를 요청했고 결과를 기다린다 */
    PROCESSING,
    /** PG 취소 성공. 충전 HP가 환불되었다 */
    COMPLETED,
    /** PG 취소 최종 실패 */
    FAILED,
    /** PG 결과가 불명확하다(타임아웃 등). 성공·실패로 단정하지 않고 확인 후 처리한다 */
    UNKNOWN
}

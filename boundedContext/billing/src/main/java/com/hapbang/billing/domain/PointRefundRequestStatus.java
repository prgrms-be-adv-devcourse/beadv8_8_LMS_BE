package com.hapbang.billing.domain;

// 여러 충전 건을 묶은 환불 요청의 전체 진행 상태와 부분 완료·실패·결과 불명확 상태를 구분한다.
// 환불 기능은 아직 구현 전이며, 상태 의미는 billing AGENTS.md 10장(충전·환불)을 따른다.
public enum PointRefundRequestStatus {
    /** 환불 요청 접수. 아직 PG에 취소를 요청하지 않았다 */
    PENDING,
    /** 충전 건별 PG 취소를 진행 중이다 */
    PROCESSING,
    /** 일부 충전 건만 환불을 마쳤다 */
    PARTIALLY_COMPLETED,
    /** 요청한 금액의 환불을 모두 마쳤다 */
    COMPLETED,
    /** 최종 실패 */
    FAILED,
    /** PG 결과가 불명확하다(타임아웃 등). 성공·실패로 단정하지 않고 확인 후 처리한다 */
    UNKNOWN
}

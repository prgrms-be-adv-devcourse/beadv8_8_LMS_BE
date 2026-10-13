package com.hapbang.billing.domain;

// PG 충전의 대기·완료·실패 및 완료된 충전 건의 부분·전체 환불 상태를 구분한다.
public enum PointChargeStatus {
    /** PG 승인 전. 충전 HP와 가용 원금에 포함하지 않는다 */
    PENDING,
    /** 충전 완료. 남은 원금(remaining_principal_hp)을 보류·환불에 FIFO로 쓴다 */
    COMPLETED,
    /** 일부 환불. 환불하지 않은 원금은 COMPLETED와 같이 쓰고, 보류에서 반환된 원금도 되돌려 받는다 */
    PARTIALLY_REFUNDED,
    /** 전부 환불. 남은 원금이 없고 더 쓰거나 되돌려 받지 않는다 */
    REFUNDED,
    /** 충전 실패. 충전 HP와 가용 원금에 포함하지 않는다 */
    FAILED
}

package com.hapbang.billing.domain;

// 향후 실제 송금에 사용할 계좌의 검증 대기·성공·실패 상태를 구분한다.
// 실제 계좌 입력·저장·검증은 아직 구현하지 않는다(구조만 준비).
public enum AccountVerificationStatus {
    /** 계좌 검증 전 */
    PENDING,
    /** 계좌 검증 성공 */
    VERIFIED,
    /** 계좌 검증 실패 */
    FAILED
}

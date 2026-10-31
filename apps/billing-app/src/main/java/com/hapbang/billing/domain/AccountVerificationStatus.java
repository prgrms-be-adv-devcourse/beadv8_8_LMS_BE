package com.hapbang.billing.domain;

// 향후 실제 송금에 사용할 계좌의 검증 대기·성공·실패 상태를 구분한다.
// 환전 신청(#285)의 테스트 계좌는 실제 확인을 하지 않으므로 PENDING으로만 저장한다. 검증 기능은 아직 없다.
public enum AccountVerificationStatus {
    /** 계좌 검증 전 */
    PENDING,
    /** 계좌 검증 성공 */
    VERIFIED,
    /** 계좌 검증 실패 */
    FAILED
}

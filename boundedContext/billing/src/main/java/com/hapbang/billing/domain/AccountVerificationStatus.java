package com.hapbang.billing.domain;

// 향후 실제 송금에 사용할 계좌의 검증 대기·성공·실패 상태를 구분한다.
public enum AccountVerificationStatus {
    PENDING,
    VERIFIED,
    FAILED
}

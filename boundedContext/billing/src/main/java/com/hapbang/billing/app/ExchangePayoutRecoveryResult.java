package com.hapbang.billing.app;

// 결과 확인(재시작·장애 복구) 한 번의 실행 결과 (#304). 건수는 환전 기준이다.
// targets = 확인 대상, completed·failed = 이번에 성공·확정 실패로 끝난 건, inProgress = 아직 결과를 모르는 건(PROCESSING·UNKNOWN),
// manualReview = 운영자 확인으로 넘어간 건, errors = 처리 중 예외(다음 실행에서 다시 확인)
public record ExchangePayoutRecoveryResult(int targets, int completed, int failed, int inProgress, int manualReview,
        int errors) {

    static final ExchangePayoutRecoveryResult EMPTY = new ExchangePayoutRecoveryResult(0, 0, 0, 0, 0, 0);
}

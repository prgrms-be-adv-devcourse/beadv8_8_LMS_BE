package com.hapbang.billing.domain;

// 잔액 대사 실행 기록에 남기는 문제 지갑의 결과 (#822, BD-146). 일치한 지갑은 남기지 않는다.
public enum WalletReconciliationResult {
    /** 캐시와 원장 합계가 다르다 */
    MISMATCHED,
    /** 재시도 뒤에도 대사하지 못했다. 다음 회차에 창과 관계없이 다시 대사한다 */
    FAILED
}

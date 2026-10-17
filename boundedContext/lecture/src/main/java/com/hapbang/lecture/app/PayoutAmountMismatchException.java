package com.hapbang.lecture.app;

/**
 * 지급 결과의 지급액이 항목 가격과 다르다 (D43). 돈이 맞지 않는 불변식 위반이라 지급 트랜잭션을 롤백하고,
 * 다시 시도해도 같은 결과이므로 지급을 격리한다 (D54).
 */
class PayoutAmountMismatchException extends IllegalStateException {

    /** 지급 실패 기록에 남기는 코드 */
    static final String CODE = "PAYOUT_AMOUNT_MISMATCH";

    PayoutAmountMismatchException(String message) {
        super(message);
    }
}

package com.hapbang.shared.lecture.domain;

/**
 * AI 추천용 구매 결과 (#712, D82). 강의가 주문 항목 상태 11개를 줄여 보낸다. 받는 쪽은 주문 항목 상태를 해석하지 않는다.
 * <p>
 * {@link #OWNED}와 {@link #PENDING}을 합친 것이 보유(같은 강의를 다시 살 수 없는 상태)다. 둘 중 하나면 추천에서 뺀다.
 * 상수 이름은 이벤트·HTTP 응답 문자열이므로 바꾸지 않는다.
 */
public enum LecturePurchaseResult {
    /** 구매 완료부터 지급 뒤까지. 시청 가능하고 같은 강의를 다시 살 수 없다. 관심 신호로 쓴다 */
    OWNED,
    /** 결제·구매 취소·반환 처리 중. 볼 수 없지만 같은 강의를 다시 살 수 없다 */
    PENDING,
    /** 구매 취소됨. 다시 살 수 있다 */
    CANCELED,
    /** 이의 제기 인정으로 반환됨. 다시 살 수 있다 */
    RETURNED,
    /** 결제 실패. 다시 살 수 있다 */
    FAILED;

    /** 보유 중인지(구매 완료 또는 처리 중). 같은 강의를 다시 살 수 없어 추천에서 뺀다 */
    public boolean isHeld() {
        return this == OWNED || this == PENDING;
    }
}

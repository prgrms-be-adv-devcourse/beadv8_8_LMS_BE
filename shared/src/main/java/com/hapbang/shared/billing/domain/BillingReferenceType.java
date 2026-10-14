package com.hapbang.shared.billing.domain;

// 원장·보류·보류 상세가 참조하는 강의·출연·Billing 거래 대상의 종류를 구분한다.
// 강의·출연이 BillingPort로 보류·반환·지급을 요청할 때 쓰는 공통 계약이며, Billing 엔티티에는 상수 이름 그대로(EnumType.STRING) 저장한다.
// 상수 이름은 DB CHECK 허용값이므로 바꾸지 않는다.
public enum BillingReferenceType {
    /** 강의 주문 전체. 보류 단위가 아니다(강의는 주문 항목마다 보류한다). 원장 등에서 주문을 가리킬 때 쓸 수 있게 남겨 둔 값 */
    LECTURE_ORDER,
    /** 강의 주문 항목(강의 하나). 강의 결제 보류의 단위이며 강의 판매 지급의 근거 */
    LECTURE_ORDER_ITEM,
    /** 출연 요청서(호스트 → 게스트 1명). 출연 요청 보류의 단위 */
    APPEARANCE_REQUEST,
    /** 출연 모집글(호스트 → 게스트 여러 명). 모집 인원 전체 출연료를 모집글 하나에 보류한다 */
    APPEARANCE_RECRUIT,
    /** 출연 모집에서 성립한 게스트 한 명의 출연. 모집 출연료 지급의 근거 */
    RECRUIT_APPEARANCE,
    /** HP 충전 건 */
    POINT_CHARGE,
    /** HP 환불 요청(여러 충전 건의 환불을 묶는다) */
    POINT_REFUND_REQUEST,
    /** 수익 HP 환전 신청 */
    EXCHANGE,
    /** 수익 HP 지급 건 */
    SETTLEMENT
}

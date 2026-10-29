package com.hapbang.shared.billing.out;

/** 지갑 응답을 받지 못한 실패의 종류 (D64). 둘 다 같은 멱등 키로 다시 보내도 HP가 두 번 움직이지 않는다 */
public enum BillingCallFailureKind {

    /** 요청이 지갑에 닿지 않은 것이 확실하다(연결 거부, 주소를 찾지 못함) */
    RETRYABLE,

    /** 지갑이 처리했는지 알 수 없다(시간 초과, 응답 도중 끊김, 5xx, 읽을 수 없는 성공 응답) */
    UNKNOWN_RESULT
}

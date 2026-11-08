package com.hapbang.shared.billing.domain;

/**
 * 지갑 보류 상태 (#577, 보류·지급 조회 내부 API 응답). 지갑의 {@code PointHoldStatus}와 같은 상수다(지갑 테스트가 일치를 확인한다).
 */
public enum BillingHoldStatus {
    /** 처음 보류한 그대로다(반환·지급 없음) */
    HELD,
    /** 일부를 반환·지급·확정 차감했고 남은 보류가 있다 */
    PARTIALLY_PROCESSED,
    /** 남은 보류가 0이고 모두 반환했다(지급 없음) */
    RELEASED,
    /** 남은 보류가 0이고 모두 지급했다(반환 없음) */
    PAID,
    /** 남은 보류가 0이고 확정 차감으로 끝났다(지갑 안의 환불·환전) */
    CAPTURED,
    /** 남은 보류가 0이고 반환·지급(또는 확정 차감)이 섞였다 */
    MIXED
}

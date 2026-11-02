package com.hapbang.shared.billing.domain;

/**
 * 보류 무효화의 결과 (#479). 어떤 결과든 그 참조 대상에는 앞으로 보류가 만들어지지 않는다(뒤늦은 보류는 {@code HOLD_VOIDED}).
 */
public enum HoldVoidOutcome {

    /** 보류가 있었고 남은 HP를 결제 회원에게 전부 반환했다 */
    RELEASED,

    /** 보류가 있었지만 이미 끝났다(전부 반환·지급). 바꾸지 않았다 */
    ALREADY_CLOSED,

    /** 보류가 없었다. 무효 표시만 남겼다 */
    VOIDED
}

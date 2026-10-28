package com.hapbang.appearance.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** HP 작업의 대상. HP 보류가 걸린 단위이며 Billing 참조 유형({@code BillingReferenceType})과 이름이 같다 */
@Getter
@RequiredArgsConstructor
public enum HpTaskRefType {
    APPEARANCE_REQUEST("출연 요청서"),
    APPEARANCE_RECRUIT("출연 모집글");

    private final String description;
}

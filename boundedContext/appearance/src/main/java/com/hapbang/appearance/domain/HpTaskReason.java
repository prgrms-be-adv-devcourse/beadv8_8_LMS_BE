package com.hapbang.appearance.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** HP 작업 사유. Billing 원장에 거래 사유로 남으며, Billing의 사유와 이름이 같다 */
@Getter
@RequiredArgsConstructor
public enum HpTaskReason {
    REQUEST_REJECTED("게스트가 출연 요청을 거절"),
    REQUEST_CANCELED("게스트 응답 전에 호스트가 출연 요청을 취소");

    // 만료·합의 취소·노쇼·지급 등은 해당 처리에서 추가한다.

    private final String description;
}

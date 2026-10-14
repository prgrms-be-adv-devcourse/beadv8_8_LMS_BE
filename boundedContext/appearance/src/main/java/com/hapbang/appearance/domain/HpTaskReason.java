package com.hapbang.appearance.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** HP 작업 사유. 출연 내부 기록이며 Billing에는 보내지 않는다 (Billing 반환·지급 요청에는 사유가 없다) */
@Getter
@RequiredArgsConstructor
public enum HpTaskReason {
    REQUEST_REJECTED("게스트가 출연 요청을 거절"),
    REQUEST_CANCELED("게스트 응답 전에 호스트가 출연 요청을 취소");

    // 만료·합의 취소·노쇼·지급 등은 해당 처리에서 추가한다.

    private final String description;
}

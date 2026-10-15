package com.hapbang.appearance.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** HP 작업 종류. Billing에 요청하는 동작과 같다 */
@Getter
@RequiredArgsConstructor
public enum HpTaskType {
    RELEASE("반환: 보류 HP를 호스트에게 되돌린다"),
    PENALTY("위약금 정산: 보류에서 위약금은 게스트에게 지급하고 나머지는 호스트에게 반환한다 (Billing settlePenalty)");
    // 지급(PAYOUT)은 출연 확인 처리에서 추가한다.

    private final String description;
}

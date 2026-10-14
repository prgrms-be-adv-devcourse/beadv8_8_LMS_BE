package com.hapbang.appearance.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** HP 작업 종류. Billing에 요청하는 동작과 같다 */
@Getter
@RequiredArgsConstructor
public enum HpTaskType {
    RELEASE("반환: 보류 HP를 호스트에게 되돌린다");

    // 지급(PAYOUT)·일괄 정산(SETTLE)은 출연 확인·취소 처리에서 추가한다.

    private final String description;
}

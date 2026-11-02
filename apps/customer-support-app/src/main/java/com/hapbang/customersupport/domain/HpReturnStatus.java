package com.hapbang.customersupport.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 이의 제기 인정에 따른 HP 반환 상태 (#279, D60 (5)). 인정한 이의 제기에만 있다.
 * 앞으로만 바뀐다({@code PENDING < ACTION_REQUIRED < COMPLETED}). 강의의 반환 이벤트가 판단 결과보다 먼저 오거나 두 번 와도
 * 뒤로 돌아가지 않는다. "환불"은 충전 HP의 현금화라 이 상태는 반환이다.
 */
@Getter
@RequiredArgsConstructor
public enum HpReturnStatus {
    /** 반환 처리 중. 인정했고 강의의 반환 작업이 HP를 돌려주는 중이다 */
    PENDING("반환 처리 중"),
    /** 반환이 자동으로 끝나지 않아 관리자 확인이 필요하다. 사용자에게는 처리 지연으로 보인다 */
    ACTION_REQUIRED("반환 지연 · 확인 중"),
    /** 반환 완료 */
    COMPLETED("반환 완료");

    private final String label;

    /** 둘 중 더 나아간 상태. 현재가 없으면 다음 상태다 */
    public static HpReturnStatus advance(HpReturnStatus current, HpReturnStatus next) {
        if (current == null) {
            return next;
        }
        return next.ordinal() > current.ordinal() ? next : current;
    }
}

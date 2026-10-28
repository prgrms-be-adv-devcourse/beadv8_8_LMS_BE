package com.hapbang.billing.domain;

import java.util.Set;

// 지급 예정 수익 통합 조회(#295)의 수익 진행 상태다. 강의·출연 내부 수익 API의 공통 상태 이름과 같고, 의미는 두 도메인의 계약을 따른다.
// 상수 이름은 응답·요청 문자열이므로 바꾸지 않는다. 앞의 다섯 상태는 아직 지급하지 않은 수익이다.
public enum EarningStatus {
    /** 구매 확정 대기(강의) / 출연 확인 대기(출연) */
    CONFIRMATION_PENDING,
    /** 지급 예정. 강의만 있다(출연은 확인하는 순간 지급한다) */
    SCHEDULED,
    /** 지급 중단: 이의 제기(강의) / 게스트 출연 취소 요청 처리 중(출연) */
    ON_HOLD,
    /** 지급 처리 중(일시 실패 뒤 재시도 포함) */
    PROCESSING,
    /** 지급 실패. 관리자 조치를 기다린다 */
    FAILED,
    /** 지급 완료 */
    COMPLETED,
    /** 받을 수익이 없다(구매 취소·반환, 불참·합의 취소) */
    CANCELED;

    /** 상태를 지정하지 않았을 때 조회하는 아직 지급하지 않은 상태 다섯 개 */
    public static final Set<EarningStatus> UNPAID =
            Set.of(CONFIRMATION_PENDING, SCHEDULED, ON_HOLD, PROCESSING, FAILED);
}

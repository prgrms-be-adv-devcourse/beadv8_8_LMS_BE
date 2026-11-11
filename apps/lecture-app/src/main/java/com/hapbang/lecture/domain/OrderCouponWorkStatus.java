package com.hapbang.lecture.domain;

/** 주문의 쿠폰 작업 상태. {@code PENDING} 밖은 모두 끝난 상태다 */
public enum OrderCouponWorkStatus {
    /** 결과를 아직 모른다. 다음 시도 시각이 지나면 같은 작업 ID·같은 내용으로 다시 보낸다 */
    PENDING,
    /** 예약 작업이 성공했다 */
    RESERVED,
    /** 쿠폰이 예약을 거절했다. 거절 코드가 남는다 */
    REJECTED,
    /** 해제 작업이 성공했다 */
    RELEASED,
    /** 시도 상한에 닿아 자동 처리를 멈췄다. 시간만으로 해제하지 않고 운영자가 확인한다 */
    DEAD
}

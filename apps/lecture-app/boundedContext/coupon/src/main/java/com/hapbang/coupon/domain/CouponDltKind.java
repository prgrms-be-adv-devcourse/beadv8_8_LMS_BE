package com.hapbang.coupon.domain;

/**
 * DLT 격리 기록을 남긴 소비자. 관리자 재처리·영구 거절이 이 값으로 갈린다.
 */
public enum CouponDltKind {
    /**
     * 발급 요청(쿠폰 이벤트 토픽, 그룹 {@code lecture-app.coupon-issue}). 재처리는 원문을 원 토픽에 다시 보내고, 영구 거절은 발급 결과를
     * 거절로 확정한 뒤 Redis 수량을 보상한다
     */
    ISSUE,
    /**
     * 주문 결제·취소 완료(강의 이벤트 토픽, 그룹 {@code lecture-app.coupon-order}). 재처리는 저장한 원문으로 소비 UseCase를 다시 실행하고
     * 강의 토픽에 다시 보내지 않는다. 영구 거절은 기록만 REJECTED로 닫는다(Redis 보상 없음)
     */
    ORDER
}

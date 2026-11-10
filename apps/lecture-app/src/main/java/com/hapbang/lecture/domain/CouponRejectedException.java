package com.hapbang.lecture.domain;

/**
 * 쿠폰 모듈이 쿠폰 사용을 거절했다. 프론트가 어떤 쿠폰이 왜 안 되는지 보이도록 쿠폰 오류 코드({@code couponCode})와,
 * 특정 발급 쿠폰 때문이면 그 ID({@code memberCouponId})를 응답에 싣는다.
 */
public class CouponRejectedException extends LectureException {

    public CouponRejectedException(LectureErrorCode errorCode, String couponCode, Long memberCouponId) {
        super(errorCode);
        if (couponCode != null) {
            addProperty("couponCode", couponCode);
        }
        if (memberCouponId != null) {
            addProperty("memberCouponId", memberCouponId);
        }
    }
}

package com.hapbang.lecture.domain;

import java.util.Set;

/**
 * 쿠폰 모듈이 쿠폰 사용을 거절했다. 프론트가 어떤 쿠폰이 왜 안 되는지 보이도록 쿠폰 오류 코드({@code couponCode})와,
 * 특정 발급 쿠폰 때문이면 그 ID({@code memberCouponId})를 응답에 싣는다.
 * <p>
 * 쿠폰이 준 사유({@code detail})는 회원에게 보여도 되는 사유 코드({@link #MEMBER_VISIBLE_DETAILS})일 때만 {@code couponDetail}로 싣는다.
 * 쿠폰의 그 밖의 detail은 운영자용 문장(강의·주문 항목 ID가 들어간 설명, 작업 상태 등)이라 계약으로 쓰지 않는다.
 */
public class CouponRejectedException extends LectureException {

    /** 회원 응답에 실어도 되는 쿠폰 거절 사유. 플랫폼 재원이 꺼져 있어 플랫폼 쿠폰을 쓸 수 없다 */
    static final Set<String> MEMBER_VISIBLE_DETAILS = Set.of("PLATFORM_FUNDING_DISABLED");

    public CouponRejectedException(LectureErrorCode errorCode, String couponCode, Long memberCouponId) {
        this(errorCode, couponCode, memberCouponId, null);
    }

    /** @param couponDetail 쿠폰이 준 detail. 회원에게 보여도 되는 사유 코드가 아니면 싣지 않는다 */
    public CouponRejectedException(LectureErrorCode errorCode, String couponCode, Long memberCouponId,
                                   String couponDetail) {
        super(errorCode);
        if (couponCode != null) {
            addProperty("couponCode", couponCode);
        }
        if (memberCouponId != null) {
            addProperty("memberCouponId", memberCouponId);
        }
        if (couponDetail != null && MEMBER_VISIBLE_DETAILS.contains(couponDetail)) {
            addProperty("couponDetail", couponDetail);
        }
    }
}

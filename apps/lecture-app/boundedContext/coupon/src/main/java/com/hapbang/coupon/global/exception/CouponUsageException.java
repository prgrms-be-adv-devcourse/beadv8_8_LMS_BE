package com.hapbang.coupon.global.exception;

/**
 * 쿠폰 사용(계산·예약·해제)의 업무 실패. 실패한 발급 쿠폰이 있으면 응답에 {@code memberCouponId} 속성으로 싣는다.
 * 도메인 규칙 위반에서 온 같은 속성은 핸들러가 {@code CouponUsageViolationException}에서 꺼내 싣는다.
 */
public class CouponUsageException extends CouponException {

    /** 응답 속성 이름: 실패한 발급 쿠폰 ID */
    public static final String MEMBER_COUPON_ID = "memberCouponId";

    /** 특정 발급 쿠폰과 관계없는 실패(작업 내용 충돌 등) */
    public CouponUsageException(CouponErrorCode errorCode) {
        super(errorCode);
    }

    /**
     * @param memberCouponId 실패한 발급 쿠폰 ID. {@code null}이면 속성을 싣지 않는다
     */
    public CouponUsageException(CouponErrorCode errorCode, Long memberCouponId) {
        super(errorCode);
        putMemberCouponId(memberCouponId);
    }

    /**
     * @param detail         응답의 detail. {@code null}이면 오류 코드의 기본 메시지
     * @param memberCouponId 실패한 발급 쿠폰 ID. {@code null}이면 속성을 싣지 않는다
     */
    public CouponUsageException(CouponErrorCode errorCode, String detail, Long memberCouponId) {
        super(errorCode, detail);
        putMemberCouponId(memberCouponId);
    }

    /** 실패한 발급 쿠폰 ID. 없으면 {@code null} */
    public Long getMemberCouponId() {
        return (Long) getProperties().get(MEMBER_COUPON_ID);
    }

    private void putMemberCouponId(Long memberCouponId) {
        if (memberCouponId != null) {
            addProperty(MEMBER_COUPON_ID, memberCouponId);
        }
    }
}

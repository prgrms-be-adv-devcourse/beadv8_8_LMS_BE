package com.hapbang.coupon.domain;

/**
 * 특정 강의 대상 쿠폰의 강의 판매 상태 규칙. 강의가 판매 중일 때만 쿠폰을 신청·발행하고 발급을 열(승인·발행) 수 있다.
 * 모든 강의 대상 쿠폰에는 쓰지 않는다.
 * <p>
 * 판매 상태는 강의 조회 내부 API가 넘긴 강의 판매 상태 이름 그대로다. 쿠폰 도메인은 강의 모듈의 enum을 참조하지 않으므로 이름을 문자열로 비교한다.
 */
public final class LectureSaleRule {

    /** 강의 판매 상태 중 판매 중 */
    public static final String ON_SALE = "ON_SALE";

    private LectureSaleRule() {
    }

    /** 판매 중이 아니면(검수 대기·반려·알 수 없는 값·없음) {@link CouponDomainError#LECTURE_NOT_ON_SALE}이다 */
    public static void requireOnSale(String saleStatus) {
        if (!ON_SALE.equals(saleStatus)) {
            throw new CouponDomainException(CouponDomainError.LECTURE_NOT_ON_SALE);
        }
    }
}

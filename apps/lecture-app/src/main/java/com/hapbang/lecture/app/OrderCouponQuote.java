package com.hapbang.lecture.app;

import java.util.List;
import java.util.Map;

import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.shared.coupon.api.CouponCalculateResponse;

/**
 * 쿠폰 주문 0단계의 계산 결과. 주문 저장({@link OrderCreateUseCase#placeWithCoupons})이 할인 스냅샷과 쿠폰별 계산 근거로 남긴다.
 *
 * @param items 강의 ID → 그 강의 항목의 계산 결과. 주문할 강의마다 하나다
 */
record OrderCouponQuote(Map<Long, Item> items) {

    OrderCouponQuote {
        items = Map.copyOf(items);
    }

    /**
     * 계산에 쓴 가격·판매자가 주문을 저장할 때 읽은 강의와 같은지 확인한다. 그 사이 가격이 바뀌었으면 계산이 맞지 않아
     * 구매자가 다시 계산해야 한다.
     */
    void requireSameLectures(List<Lecture> lectures) {
        for (Lecture lecture : lectures) {
            Item item = items.get(lecture.getId());
            if (item == null || item.priceHp() != lecture.getPriceHp()
                    || !item.sellerUserId().equals(lecture.getSellerUserId())) {
                throw new LectureException(LectureErrorCode.ORDER_QUOTE_CHANGED);
            }
        }
    }

    /**
     * 강의 항목 하나의 계산 결과.
     *
     * @param priceHp            계산에 쓴 강의 가격
     * @param sellerUserId       계산에 쓴 판매자
     * @param discountHp         할인 합
     * @param platformDiscountHp 플랫폼 부담
     * @param sellerDiscountHp   판매자 부담
     * @param coupons            적용 순서대로의 쿠폰별 계산 근거. 쿠폰이 없으면 빈 목록
     */
    record Item(long priceHp, Long sellerUserId, long discountHp, long platformDiscountHp, long sellerDiscountHp,
                List<CouponCalculateResponse.Coupon> coupons) {

        Item {
            coupons = List.copyOf(coupons);
        }
    }
}

package com.hapbang.coupon.app.dto;

import java.time.Instant;

import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponType;
import com.hapbang.coupon.domain.CouponUsage;
import com.hapbang.coupon.domain.CouponUsageStatus;
import com.hapbang.coupon.domain.DiscountType;
import com.hapbang.coupon.domain.MemberCouponStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 회원의 쿠폰 사용 이력 한 건(발급 쿠폰 한 장을 주문 항목 하나에 쓴 시도). 부담 주체 금액(플랫폼·판매자 몫)·적용 직전 금액·적용 순서·
 * generation·작업 ID·해제 사유는 회원에게 내보내지 않는다. 강의 제목은 싣지 않는다(강의 API로 읽는다).
 * 표시 상태는 {@code status}로만 정한다. 늦게 온 결제·취소 완료는 상태를 바꾸지 않고 시각만 남기므로, 예를 들어 RELEASED 행에도
 * {@code paidAt}·{@code canceledAt}이 있을 수 있다.
 *
 * @param couponType     쿠폰 종류(SELLER·PLATFORM). 예약 때 남긴 값
 * @param discountType   쿠폰 정책의 할인 방식
 * @param discountValue  쿠폰 정책의 할인 값(정률이면 %, 정액이면 HP)
 * @param itemPrice      주문 항목 원가격 P(HP)
 * @param discountAmount 이 쿠폰의 할인 d(HP)
 * @param returnedTo     돌려받은 뒤 발급 쿠폰 상태(AVAILABLE·EXPIRED). 해제면 해제 결과, 취소 완료면 복원 결과, 그 밖에는 null
 * @param paidAt         결제 완료 시각. 결제 완료가 없었으면 null
 * @param releasedAt     해제 시각. 해제하지 않았으면 null
 * @param canceledAt     구매 취소(반환) 완료 시각. 취소 완료가 없었으면 null
 * @param returnedAt     돌려받은 시각. {@code returnedTo}와 같은 갈래(해제·복원) 시각
 */
public record CouponUsageView(
        Long usageId,
        Long memberCouponId,
        Long couponId,
        String couponName,
        CouponType couponType,
        DiscountType discountType,
        long discountValue,
        Long lectureId,
        Long orderId,
        Long orderItemId,
        long itemPrice,
        long discountAmount,
        @Schema(description = "RESERVED(결제 진행 중) · PAID(사용함) · RELEASED(결제되지 않아 돌려받음) · "
                + "CANCELED(구매 취소로 돌려받음). RELEASED·CANCELED이고 returnedTo=EXPIRED면 \"기한이 지나 만료됨\"")
        CouponUsageStatus status,
        @Schema(description = "돌려받은 뒤 발급 쿠폰 상태(AVAILABLE·EXPIRED). RELEASED면 해제 결과, CANCELED면 복원 결과, 그 밖에는 null",
                nullable = true)
        MemberCouponStatus returnedTo,
        Instant reservedAt,
        Instant paidAt,
        Instant releasedAt,
        Instant canceledAt,
        Instant returnedAt
) {

    /** @param coupon 사용 시도의 쿠폰 정책 */
    public static CouponUsageView of(CouponUsage usage, Coupon coupon) {
        MemberCouponStatus returnedTo = null;
        Instant returnedAt = null;
        if (usage.getStatus() == CouponUsageStatus.RELEASED) {
            returnedTo = usage.getReleasedTo();
            returnedAt = usage.getReleasedAt();
        } else if (usage.getStatus() == CouponUsageStatus.CANCELED) {
            returnedTo = usage.getRestoredTo();
            returnedAt = usage.getRestoredAt();
        }
        return new CouponUsageView(usage.getId(), usage.getMemberCouponId(), usage.getCouponId(), coupon.getName(),
                usage.getCouponType(), coupon.getDiscountType(), coupon.getDiscountValue(), usage.getLectureId(),
                usage.getOrderId(), usage.getOrderItemId(), usage.getItemPrice(), usage.getDiscountAmount(),
                usage.getStatus(), returnedTo, usage.getReservedAt(), usage.getPaidAt(), usage.getReleasedAt(),
                usage.getCancelCompletedAt(), returnedAt);
    }
}

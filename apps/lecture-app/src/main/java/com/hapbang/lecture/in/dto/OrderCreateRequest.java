package com.hapbang.lecture.in.dto;

import java.util.List;
import java.util.Objects;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

@Schema(description = "장바구니 주문은 담은 강의 ID들, 바로 구매는 강의 ID 1개. 쿠폰을 쓰면 고른 쿠폰과 미리보기의 결제 금액을 함께 보낸다")
public record OrderCreateRequest(
        // 요청 하나로 HOLD 작업·지갑 호출을 무제한 만들지 못하게 한다. 요청 수 한도(D80)와 함께 쓴다
        @Schema(description = "주문할 강의 ID. 1~100개")
        @NotEmpty @Size(max = MAX_LECTURES) List<@NotNull Long> lectureIds,

        @Schema(description = "고른 쿠폰과 그 쿠폰을 쓸 강의. 없거나 비면 쿠폰 없이 주문한다. 강의 하나에 판매자·플랫폼 쿠폰 1장씩까지")
        @Size(max = MAX_COUPONS) List<@NotNull @Valid Coupon> coupons,

        @Schema(description = "쿠폰 할인 미리보기에서 본 결제 금액 합(HP). 쿠폰을 쓰면 필수다. 서버가 다시 계산한 금액과 다르면 "
                + "409 ORDER_QUOTE_CHANGED")
        @PositiveOrZero Long expectedBuyerAmountHp
) {

    /** 한 주문에 담을 수 있는 강의 수 (D80). 장바구니에는 상한이 없다 */
    public static final int MAX_LECTURES = 100;
    /** 한 주문에 쓸 수 있는 쿠폰 수. 강의 하나에 판매자·플랫폼 쿠폰 1장씩까지다 */
    public static final int MAX_COUPONS = MAX_LECTURES * 2;

    /** 쿠폰을 골랐는지 */
    public boolean hasCoupons() {
        return coupons != null && !coupons.isEmpty();
    }

    @Schema(hidden = true)
    @AssertTrue(message = "쿠폰을 쓰는 주문은 결제 금액(expectedBuyerAmountHp)을 함께 보내야 합니다.")
    public boolean isExpectedAmountPresentWithCoupons() {
        return !hasCoupons() || expectedBuyerAmountHp != null;
    }

    @Schema(hidden = true)
    @AssertTrue(message = "쿠폰을 쓸 강의는 주문할 강의 중 하나여야 합니다.")
    public boolean isCouponLectureOrdered() {
        return !hasCoupons() || lectureIds == null || coupons.stream()
                .filter(Objects::nonNull)
                .allMatch(coupon -> coupon.lectureId() == null || lectureIds.contains(coupon.lectureId()));
    }

    /**
     * 고른 쿠폰 한 장.
     *
     * @param memberCouponId 발급 쿠폰 ID
     * @param lectureId      쿠폰을 쓸 강의 ID
     */
    public record Coupon(
            @Schema(description = "발급 쿠폰 ID") @NotNull Long memberCouponId,
            @Schema(description = "쿠폰을 쓸 강의 ID(lectureIds 중 하나)") @NotNull Long lectureId
    ) {
    }
}
